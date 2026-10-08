package kb_bridge.external.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.DisclosureBatch;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.external.dart.dto.DartCompanyResponse;
import kb_bridge.external.dart.dto.DartDisclosureResponse;
import kb_bridge.external.dart.dto.DartFinancialResponse;

@Component
public class DartClient {

    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int FINANCIAL_LOOKBACK_YEARS = 5;

    public static class CompanyNotFoundException extends IllegalArgumentException {
        public CompanyNotFoundException(String companyName) {
            super("OpenDART 기업코드를 찾을 수 없습니다: " + companyName);
        }
    }

    public static class AmbiguousCompanyException extends IllegalArgumentException {
        public AmbiguousCompanyException(String companyName) {
            super("OpenDART 기업코드가 여러 개 검색되었습니다. 회사명을 더 구체적으로 입력하세요: " + companyName);
        }
    }

    private final RestClient restClient;
    private final String apiKey;
    private volatile Map<String, String> corpCodes;

    public DartClient(
            @Value("${dart.api.base-url}") String baseUrl,
            @Value("${dart.api.key}") String apiKey
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.apiKey = normalizeApiKey(apiKey);
    }

    public DartCompanyResponse getCompany(String corpCode) {
        ensureApiKey();
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/company.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .build())
                .retrieve()
                .body(DartCompanyResponse.class);
    }

    public String resolveCorpCode(String companyName) {
        ensureApiKey();
        Map<String, String> codes = corpCodes;
        if (codes == null) {
            synchronized (this) {
                codes = corpCodes;
                if (codes == null) {
                    codes = loadCorpCodes();
                    corpCodes = codes;
                }
            }
        }

        String normalizedName = normalizeName(companyName);
        List<String> matches = codes.entrySet().stream()
                .filter(entry -> normalizeName(entry.getKey()).equals(normalizedName))
                .map(Map.Entry::getValue)
                .toList();
        if (matches.isEmpty()) {
            matches = codes.entrySet().stream()
                    .filter(entry -> normalizeName(entry.getKey()).contains(normalizedName))
                    .map(Map.Entry::getValue)
                    .toList();
        }
        if (matches.size() != 1) {
            if (matches.isEmpty()) {
                throw new CompanyNotFoundException(companyName);
            }
            throw new AmbiguousCompanyException(companyName);
        }
        return matches.get(0);
    }

    public FinancialSnapshot getRecentFinancials(String corpCode) {
        int currentYear = LocalDate.now().getYear();
        DartFinancialResponse current = null;
        int fiscalYear = currentYear;
        for (int year = currentYear; year > currentYear - FINANCIAL_LOOKBACK_YEARS; year--) {
            DartFinancialResponse response = getAnnualFinancials(corpCode, year, "CFS");
            if (response == null || response.list() == null || response.list().isEmpty()) {
                response = getAnnualFinancials(corpCode, year, "OFS");
            }
            if (response != null && response.list() != null && !response.list().isEmpty()) {
                current = response;
                fiscalYear = year;
                break;
            }
        }
        if (current == null) {
            return null;
        }

        DartFinancialResponse previous = getAnnualFinancials(corpCode, fiscalYear - 1, "CFS");
        if (previous == null || previous.list() == null || previous.list().isEmpty()) {
            previous = getAnnualFinancials(corpCode, fiscalYear - 1, "OFS");
        }
        List<DartFinancialResponse.Item> currentItems = current.list();
        List<DartFinancialResponse.Item> previousItems =
                previous == null || previous.list() == null ? List.of() : previous.list();
        BigDecimal currentShortTermDebt = findExactAmount(currentItems, "단기차입금");
        BigDecimal priorShortTermDebt = findExactAmount(previousItems, "단기차입금");
        BigDecimal currentLongTermDebt = findExactAmount(currentItems, "장기차입금");
        BigDecimal priorLongTermDebt = findExactAmount(previousItems, "장기차입금");
        return new FinancialSnapshot(
                Integer.toString(fiscalYear),
                findAmount(currentItems, "매출액", "영업수익", "수익"),
                findAmount(currentItems, "영업이익"),
                currentShortTermDebt,
                priorShortTermDebt,
                sum(currentShortTermDebt, currentLongTermDebt),
                sum(priorShortTermDebt, priorLongTermDebt),
                findAmount(currentItems, "영업활동 현금흐름", "영업활동으로 인한 현금흐름")
        );
    }

    public DisclosureBatch getRecentDisclosures(String corpCode, LocalDate from, LocalDate to) {
        ensureApiKey();
        List<DartDisclosureResponse.Item> items = new ArrayList<>();
        int totalPages = 1;
        for (int page = 1; page <= totalPages; page++) {
            int pageNumber = page;
            DartDisclosureResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/list.json")
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bgn_de", from.format(DART_DATE))
                            .queryParam("end_de", to.format(DART_DATE))
                            .queryParam("page_no", pageNumber)
                            .queryParam("page_count", 100)
                            .queryParam("sort", "date")
                            .queryParam("sort_mth", "desc")
                            .build())
                    .retrieve()
                    .body(DartDisclosureResponse.class);
            if (response == null) {
                throw new IllegalStateException("OpenDART returned an empty recent disclosure response.");
            }
            if ("013".equals(response.status())) {
                return new DisclosureBatch(List.of(), List.of());
            }
            checkStatus(response.status(), response.message(), "recent disclosure");
            if (response.list() != null) {
                items.addAll(response.list());
            }
            if (response.totalPage() != null) {
                totalPages = response.totalPage();
            }
        }

        List<DisclosureEvidence> evidence = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (DartDisclosureResponse.Item item : items) {
            if (!isPotentialPlanDisclosure(item.reportName())) {
                continue;
            }
            try {
                evidence.add(new DisclosureEvidence(
                        "OpenDART",
                        item.reportName(),
                        formatDisclosureDate(item.receiptDate()),
                        disclosureUrl(item.receiptNumber()),
                        item.receiptNumber(),
                        isCorrection(item.reportName()) ? "UNRESOLVED" : "NOT_CORRECTION",
                        getDisclosureText(item.receiptNumber()),
                        null
                ));
            } catch (org.springframework.web.client.RestClientException | IOException | IllegalStateException e) {
                messages.add("공시 원문 조회 실패: " + item.receiptNumber() + " (" + item.reportName()
                        + "). 원인: " + safeFailureDetail(e));
            }
        }
        return new DisclosureBatch(resolveCorrections(evidence), List.copyOf(messages));
    }

    private String safeFailureDetail(Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String sanitized = detail
                .replaceAll("(?i)(crtfc_key=)[^&\\s]+", "$1[redacted]")
                .replaceAll("[0-9a-fA-F]{40}", "[redacted]")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.substring(0, Math.min(sanitized.length(), 240));
    }

    private String getDisclosureText(String receiptNumber) throws IOException {
        byte[] archive = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/document.xml")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("rcept_no", receiptNumber)
                        .build())
                .retrieve()
                .body(byte[].class);
        if (archive == null || archive.length == 0) {
            throw new IllegalStateException("OpenDART returned an empty disclosure document.");
        }

        return extractDisclosureText(archive);
    }

    String extractDisclosureText(byte[] archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            if (zip.getNextEntry() == null) {
                throw new IOException("OpenDART disclosure document archive is empty.");
            }
            byte[] documentBytes = zip.readAllBytes();
            Document document = Jsoup.parse(new ByteArrayInputStream(documentBytes), null, "");
            document.select("script, style").remove();
            return document.text().replaceAll("\\s+", " ").trim();
        }
    }

    private List<DisclosureEvidence> resolveCorrections(List<DisclosureEvidence> evidence) {
        return evidence.stream().map(disclosure -> {
            if (!isCorrection(disclosure.title())) {
                return disclosure;
            }
            String normalizedTitle = normalizeReportTitle(disclosure.title());
            long matchingOriginals = evidence.stream()
                    .filter(candidate -> !candidate.receiptNumber().equals(disclosure.receiptNumber()))
                    .filter(candidate -> !isCorrection(candidate.title()))
                    .filter(candidate -> normalizeReportTitle(candidate.title()).equals(normalizedTitle))
                    .filter(candidate -> candidate.date() == null
                            || disclosure.date() == null
                            || candidate.date().compareTo(disclosure.date()) <= 0)
                    .count();
            String status = matchingOriginals == 1 ? "LINKED" : "UNRESOLVED";
            return new DisclosureEvidence(
                    disclosure.source(),
                    disclosure.title(),
                    disclosure.date(),
                    disclosure.url(),
                    disclosure.receiptNumber(),
                    status,
                    disclosure.content(),
                    excerpt(disclosure.content())
            );
        }).toList();
    }

    private String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        int keywordIndex = normalized.indexOf("투자");
        if (keywordIndex < 0) {
            keywordIndex = normalized.indexOf("차입");
        }
        if (keywordIndex < 0) {
            keywordIndex = normalized.indexOf("해외");
        }
        int start = Math.max(0, keywordIndex - 120);
        return normalized.substring(start, Math.min(normalized.length(), start + 400));
    }

    private boolean isPotentialPlanDisclosure(String title) {
        if (title == null) {
            return false;
        }
        String normalized = title.replaceAll("\\s+", "");
        return normalized.matches(".*(시설|투자|자산취득|공장|차입|회사채|사채발행|유상증자|전환사채|해외|외국법인|정정).*");
    }

    private boolean isCorrection(String title) {
        return title != null && title.contains("정정");
    }

    private String normalizeReportTitle(String title) {
        return title.replaceAll("\\s+", "")
                .replaceAll("\\[.*?정정.*?\\]", "")
                .replaceAll("\\(.*?정정.*?\\)", "")
                .replaceAll("정정", "");
    }

    private String disclosureUrl(String receiptNumber) {
        return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNumber;
    }

    private DartFinancialResponse getAnnualFinancials(String corpCode, int year, String financialStatementDivision) {
        ensureApiKey();
        DartFinancialResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/fnlttSinglAcnt.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", "11011")
                        .queryParam("fs_div", financialStatementDivision)
                        .build())
                .retrieve()
                .body(DartFinancialResponse.class);
        if (response == null || "013".equals(response.status())) {
            return null;
        }
        checkStatus(response.status(), response.message(), "annual financial statements");
        return response;
    }

    private Map<String, String> loadCorpCodes() {
        byte[] archive = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/corpCode.xml")
                        .queryParam("crtfc_key", apiKey)
                        .build())
                .retrieve()
                .body(byte[].class);
        if (archive == null || archive.length == 0) {
            throw new IllegalStateException("OpenDART returned an empty corporate-code archive.");
        }

        Map<String, String> codes = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException("OpenDART corporate-code archive is empty.");
            }
            XMLStreamReader reader = factory.createXMLStreamReader(zip);
            String corpCode = null;
            String corpName = null;
            String currentElement = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    currentElement = reader.getLocalName();
                } else if (event == XMLStreamReader.CHARACTERS && currentElement != null) {
                    if ("corp_code".equals(currentElement)) {
                        corpCode = reader.getText().trim();
                    } else if ("corp_name".equals(currentElement)) {
                        corpName = reader.getText().trim();
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    if ("list".equals(reader.getLocalName())) {
                        if (corpName != null && corpCode != null) {
                            codes.put(corpName, corpCode);
                        }
                        corpCode = null;
                        corpName = null;
                    }
                    currentElement = null;
                }
            }
            reader.close();
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException("Unable to parse OpenDART corporate-code archive.", e);
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("OpenDART corporate-code archive contained no companies.");
        }
        return Map.copyOf(codes);
    }

    private BigDecimal findAmount(List<DartFinancialResponse.Item> items, String... accountNames) {
        for (String accountName : accountNames) {
            for (DartFinancialResponse.Item item : items) {
                if (item.accountName() != null && item.accountName().contains(accountName)) {
                    String amount = item.currentAmount();
                    if (amount != null && !amount.isBlank() && !"-".equals(amount)) {
                        try {
                            return new BigDecimal(amount.replace(",", ""));
                        } catch (NumberFormatException e) {
                            throw new IllegalStateException(
                                    "Invalid amount in OpenDART financial response: " + amount, e);
                        }
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal sum(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    private BigDecimal findExactAmount(List<DartFinancialResponse.Item> items, String accountName) {
        for (DartFinancialResponse.Item item : items) {
            if (item.accountName() != null && accountName.equals(item.accountName().trim())) {
                String amount = item.currentAmount();
                if (amount != null && !amount.isBlank() && !"-".equals(amount)) {
                    try {
                        return new BigDecimal(amount.replace(",", ""));
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(
                                "Invalid amount in OpenDART financial response: " + amount, e);
                    }
                }
            }
        }
        return null;
    }

    private String normalizeName(String value) {
        return value.toLowerCase()
                .replaceAll("^[\\(（]주[\\)）]", "")
                .replaceAll("[\\(（]주[\\)）]$", "")
                .replaceAll("^주식회사|주식회사$", "")
                .replaceAll("^유한회사|유한회사$", "")
                .replaceAll("[\\s()（）㈜·.,]", "");
    }

    private String formatDisclosureDate(String date) {
        if (date == null || date.length() != 8) {
            return date;
        }
        return LocalDate.parse(date, DART_DATE).toString();
    }

    private void ensureApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set DART_API_KEY to an OpenDART-issued key before querying OpenDART.");
        }
        if (!apiKey.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException(
                    "DART_API_KEY must be the 40-character hexadecimal key issued by OpenDART. "
                            + "OPEN_API_KEY is reserved for OpenAI.");
        }
    }

    private String normalizeApiKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                        || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private void checkStatus(String status, String message, String operation) {
        if (!"000".equals(status)) {
            throw new IllegalStateException(
                    "OpenDART " + operation + " failed (" + status + "): " + message);
        }
    }
}
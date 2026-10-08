package kb_bridge.external.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.external.dart.dto.DartCompanyResponse;
import kb_bridge.external.dart.dto.DartDisclosureResponse;
import kb_bridge.external.dart.dto.DartFinancialResponse;

@Component
public class DartClient {

    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.BASIC_ISO_DATE;

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
        this.apiKey = apiKey;
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
            throw new IllegalArgumentException(
                    matches.isEmpty()
                            ? "OpenDART 기업코드를 찾을 수 없습니다: " + companyName
                            : "OpenDART 기업코드가 여러 건 검색되었습니다: " + companyName);
        }
        return matches.get(0);
    }

    public FinancialSnapshot getRecentFinancials(String corpCode) {
        int currentYear = LocalDate.now().getYear();
        DartFinancialResponse current = null;
        int fiscalYear = currentYear;
        for (int year = currentYear; year >= currentYear - 3; year--) {
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
        return new FinancialSnapshot(
                Integer.toString(fiscalYear),
                findAmount(currentItems, "매출액", "영업수익", "수익"),
                findAmount(currentItems, "영업이익"),
                findAmount(currentItems, "단기차입금"),
                findAmount(previousItems, "단기차입금"),
                findAmount(currentItems, "영업활동 현금흐름", "영업활동으로 인한 현금흐름")
        );
    }

    public List<DisclosureEvidence> getRecentDisclosures(String corpCode) {
        ensureApiKey();
        String startDate = LocalDate.now().minusYears(1).format(DART_DATE);
        String endDate = LocalDate.now().format(DART_DATE);
        DartDisclosureResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/list.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bgn_de", startDate)
                        .queryParam("end_de", endDate)
                        .queryParam("page_no", 1)
                        .queryParam("page_count", 100)
                        .queryParam("sort", "date")
                        .queryParam("sort_mth", "desc")
                        .build())
                .retrieve()
                .body(DartDisclosureResponse.class);
        if (response == null || "013".equals(response.status())) {
            return List.of();
        }
        checkStatus(response.status(), response.message(), "recent disclosure");
        if (response.list() == null) {
            return List.of();
        }
        return response.list().stream()
                .map(item -> new DisclosureEvidence(
                        "OpenDART",
                        item.reportName(),
                        formatDisclosureDate(item.receiptDate()),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + item.receiptNumber()
                ))
                .toList();
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

    private String normalizeName(String value) {
        return value.toLowerCase()
                .replaceAll("^\\(주\\)|\\(주\\)$", "")
                .replaceAll("^주식회사|주식회사$", "")
                .replaceAll("^유한회사|유한회사$", "")
                .replaceAll("[\\s(),.]", "");
    }

    private String formatDisclosureDate(String date) {
        if (date == null || date.length() != 8) {
            return date;
        }
        return LocalDate.parse(date, DART_DATE).toString();
    }

    private void ensureApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DART_API_KEY must be configured to query OpenDART.");
        }
    }

    private void checkStatus(String status, String message, String operation) {
        if (!"000".equals(status)) {
            throw new IllegalStateException(
                    "OpenDART " + operation + " failed (" + status + "): " + message);
        }
    }
}
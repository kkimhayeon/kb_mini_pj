package kb_bridge.external.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.springframework.web.client.RestClientException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

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
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
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
        for (int year = currentYear; year >= currentYear - 3; year--) {
            for (String basis : List.of("CFS", "OFS")) {
                DartFinancialResponse response = getAnnualFinancials(corpCode, year, basis);
                if (response != null && !response.list().isEmpty()) {
                    // Prior-year amounts come from this exact report, including its restatements.
                    return FinancialNormalizer.normalize(year, basis, response.list());
                }
            }
        }
        return null;
    }

    public record DisclosureBatch(List<DisclosureEvidence> items, boolean truncated, boolean detailsIncomplete) {
        public DisclosureBatch(List<DisclosureEvidence> items, boolean truncated) { this(items, truncated, false); }
    }

    public List<DisclosureEvidence> getRecentDisclosures(String corpCode) {
        return getDisclosureBatch(corpCode).items();
    }

    public DisclosureBatch getDisclosureBatch(String corpCode) {
        ensureApiKey();
        String startDate = LocalDate.now().minusYears(1).format(DART_DATE);
        String endDate = LocalDate.now().format(DART_DATE);
        Map<String, DisclosureEvidence> found = new LinkedHashMap<>();
        boolean incomplete = false;
        Integer expectedCount = null;
        int expectedPages = 0;
        for (int page = 1; ; page++) {
            DartDisclosureResponse response;
            try {
                response = disclosurePage(corpCode, startDate, endDate, page);
            } catch (RestClientException | IllegalStateException exception) {
                if (page == 1) throw exception;
                incomplete = true;
                break;
            }
            if ("013".equals(response.status())) {
                incomplete |= page <= expectedPages || (expectedCount != null && found.size() < expectedCount);
                break;
            }
            if (response.totalPage() != null) expectedPages = response.totalPage();
            if (expectedCount != null && response.totalCount() != null
                    && !expectedCount.equals(response.totalCount())) incomplete = true;
            if (response.totalCount() != null) expectedCount = response.totalCount();
            int before = found.size();
            for (var item : response.list()) {
                if (item == null || item.receiptNumber() == null || !item.receiptNumber().matches("[0-9]{14}")
                        || item.reportName() == null || item.reportName().isBlank()) {
                    incomplete = true;
                    continue;
                }
                String date;
                try { date = formatDisclosureDate(item.receiptDate()); }
                catch (java.time.DateTimeException exception) { incomplete = true; continue; }
                found.putIfAbsent(item.receiptNumber(), new DisclosureEvidence("OpenDART",
                        item.reportName(), date, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + item.receiptNumber()));
            }
            boolean last = response.totalPage() != null ? page >= response.totalPage()
                    : expectedCount != null ? page * 100L >= expectedCount : response.list().size() < 100;
            if (last) {
                incomplete |= expectedCount != null && found.size() < expectedCount;
                break;
            }
            // Broken pagination must not hang an analysis or be presented as a complete result.
            if (found.size() == before || page >= 200) { incomplete = true; break; }
        }
        var enriched = new DartDisclosureDetails(restClient, apiKey).enrich(corpCode, startDate, endDate, List.copyOf(found.values()));
        return new DisclosureBatch(enriched.items(), incomplete, enriched.incomplete());
    }

    private DartDisclosureResponse disclosurePage(String corpCode, String start, String end, int page) {
        var response = restClient.get().uri(uri -> uri.path("/list.json")
                .queryParam("crtfc_key", apiKey).queryParam("corp_code", corpCode)
                .queryParam("bgn_de", start).queryParam("end_de", end)
                .queryParam("last_reprt_at", "Y").queryParam("page_no", page).queryParam("page_count", 100)
                .queryParam("sort", "date").queryParam("sort_mth", "desc").build())
                .retrieve().body(DartDisclosureResponse.class);
        if (response == null) throw new IllegalStateException("Empty disclosure response.");
        if ("013".equals(response.status())) return response;
        checkStatus(response.status(), response.message(), "disclosures");
        if (response.list() == null) throw new IllegalStateException("Missing disclosure list.");
        return response;
    }

    private DartFinancialResponse getAnnualFinancials(String corpCode, int year, String financialStatementDivision) {
        ensureApiKey();
        DartFinancialResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", year)
                        .queryParam("reprt_code", "11011")
                        .queryParam("fs_div", financialStatementDivision)
                        .build())
                .retrieve()
                .body(DartFinancialResponse.class);
        if (response == null) {
            throw new IllegalStateException("OpenDART returned an empty financial response.");
        }
        if ("013".equals(response.status())) {
            return null;
        }
        checkStatus(response.status(), response.message(), "annual financial statements");
        if (response.list() == null) {
            throw new IllegalStateException("OpenDART returned no financial list.");
        }
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

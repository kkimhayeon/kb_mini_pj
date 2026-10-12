package kb_bridge.external.dart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.DisclosureEvidence.Details;

final class DartDisclosureDetails {
    record Result(List<DisclosureEvidence> items, boolean incomplete) {}
    private record Kind(String endpoint, String keyword, String nameField, String dateField) {}
    private static final List<Kind> KINDS = List.of(
            new Kind("/tgastInhDecsn.json", "유형자산양수", "ast_nm", "inh_prd_inh_std"),
            new Kind("/otcprStkInvscrInhDecsn.json", "타법인주식및출자증권양수", "iscmp_cmpnm", "inh_prd"));
    private final RestClient client;
    private final String key;
    DartDisclosureDetails(RestClient client, String key) { this.client = client; this.key = key; }

    Result enrich(String corpCode, String start, String end, List<DisclosureEvidence> evidence) {
        Map<String, Details> details = new HashMap<>();
        Set<String> supported = new HashSet<>();
        boolean incomplete = false;
        for (Kind kind : KINDS) {
            List<DisclosureEvidence> candidates = evidence.stream()
                    .filter(item -> item.title().replaceAll("\\s+", "").contains(kind.keyword())).toList();
            if (candidates.isEmpty()) continue;
            candidates.forEach(item -> supported.add(item.url()));
            try {
                JsonNode response = client.get().uri(uri -> uri.path(kind.endpoint())
                        .queryParam("crtfc_key", key).queryParam("corp_code", corpCode)
                        .queryParam("bgn_de", start).queryParam("end_de", end).build()).retrieve().body(JsonNode.class);
                if (response == null || !"000".equals(response.path("status").asText()) || !response.path("list").isArray()) {
                    incomplete = true; continue;
                }
                Set<String> ambiguous = new HashSet<>();
                for (JsonNode row : response.path("list")) {
                    String receipt = row.path("rcept_no").asText("");
                    if (!receipt.matches("[0-9]{14}") || !corpCode.equals(row.path("corp_code").asText(""))) continue;
                    String name = row.path(kind.nameField()).asText("").trim();
                    BigDecimal amount = decimal(row.path("inhdtl_inhprc").asText(""));
                    LocalDate date = date(row.path(kind.dateField()).asText(""));
                    String nationality = row.path("iscmp_nt").asText("").trim();
                    String domain = !nationality.isEmpty() && !Set.of("-", "미상", "미확인", "N/A", "UNKNOWN", "대한민국", "한국", "KOREA", "KR", "국내").contains(nationality.toUpperCase(Locale.ROOT))
                            ? "FX" : "INVESTMENT";
                    Details value = new Details(domain, name, amount, "KRW", date, "DAY", receipt,
                            kind.endpoint() + ": " + kind.nameField() + ", inhdtl_inhprc, " + kind.dateField());
                    if (details.putIfAbsent(receipt, value) != null) ambiguous.add(receipt);
                    if (name.isBlank() || amount == null || date == null) incomplete = true;
                }
                ambiguous.forEach(details::remove);
                incomplete |= !ambiguous.isEmpty();
            } catch (RestClientException | IllegalStateException exception) { incomplete = true; }
        }
        List<DisclosureEvidence> result = new ArrayList<>();
        for (DisclosureEvidence item : evidence) {
            String receipt = item.url().substring(item.url().lastIndexOf('=') + 1);
            Details value = details.get(receipt);
            if (supported.contains(item.url()) && value == null) incomplete = true;
            result.add(new DisclosureEvidence(item.source(), item.title(), item.date(), item.url(), value));
        }
        return new Result(List.copyOf(result), incomplete);
    }
    private static BigDecimal decimal(String text) {
        String cleaned = text.replace(",", "").trim();
        if (!cleaned.matches("[0-9]+(\\.[0-9]+)?")) return null;
        return new BigDecimal(cleaned);
    }
    private static LocalDate date(String text) {
        String cleaned = text.trim().replaceAll("[.]", "-").replace(" ", "");
        if (!cleaned.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(cleaned); } catch (java.time.DateTimeException exception) { return null; }
    }
}

package kb_bridge.external.dart;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DartPaginationTest {
    private HttpServer server;
    private DartClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Function<String, String> response;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            requests.add(uri);
            byte[] body = response.apply(uri).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new DartClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }
    @AfterEach void stop() { server.stop(0); }
    private String page(int number, int count, int pages, String title) {
        return """
                {"status":"000","total_count":%d,"total_page":%d,
                 "list":[{"report_nm":"%s","rcept_no":"2026060100000%d","rcept_dt":"20260601"}]}
                """.formatted(count, pages, title, number);
    }
    @Test void retrievesAllPagesWithFinalReportsAndDeduplicatesReceipts() {
        response = uri -> page(uri.contains("page_no=1") ? 1 : 2, 2, 2, "시설투자 결정");
        var result = client.getDisclosureBatch("00000001");
        assertThat(result.items()).hasSize(2);
        assertThat(result.truncated()).isFalse();
        assertThat(requests).hasSize(2).allSatisfy(uri -> {
            assertThat(uri).contains("last_reprt_at=Y", "page_count=100");
        });
    }
    @ParameterizedTest @ValueSource(strings = {"020", "013"})
    void laterPageFailuresKeepAlreadyRetrievedDisclosures(String status) {
        response = uri -> uri.contains("page_no=1") ? page(1, 2, 2, "시설투자 결정")
                : "{\"status\":\"" + status + "\"}";
        var result = client.getDisclosureBatch("00000001");
        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }
    @Test void emptyLaterPageIsPartialEvenWithoutTotalCount() {
        response = uri -> uri.contains("page_no=1")
                ? page(1, 2, 2, "시설투자 결정").replace("\"total_count\":2,", "")
                : "{\"status\":\"013\"}";
        assertThat(client.getDisclosureBatch("00000001").truncated()).isTrue();
    }
    @Test void repeatingPagesDoNotLoopOrDuplicateEvidence() {
        response = uri -> page(1, 300, 3, "시설투자 결정");
        var result = client.getDisclosureBatch("00000001");
        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(requests).hasSize(2);
    }
    private String detail(String receipt) {
        return """
                {"status":"000","list":[{"corp_code":"00000001","rcept_no":"%s",
                "ast_nm":"서울물류센터","inhdtl_inhprc":"1,500","inh_prd_inh_std":"2026.09.30"}]}
                """.formatted(receipt);
    }
    @Test void structuredDetailsJoinByReceiptAndKeepSourceFields() {
        response = uri -> uri.startsWith("/list.json") ? page(1, 1, 1, "주요사항보고서(유형자산양수결정)")
                : detail("20260601000001");
        var result = client.getDisclosureBatch("00000001");
        var details = result.items().getFirst().details();
        assertThat(result.detailsIncomplete()).isFalse();
        assertThat(details.amount()).isEqualByComparingTo("1500");
        assertThat(details.projectName()).isEqualTo("서울물류센터");
        assertThat(details.expectedAt()).hasToString("2026-09-30");
        assertThat(details.currency()).isEqualTo("KRW");
        assertThat(details.sourceField()).contains("inhdtl_inhprc", "inh_prd_inh_std");
    }
    @Test void anotherReceiptCannotSupplyMissingDetails() {
        response = uri -> uri.startsWith("/list.json") ? page(1, 1, 1, "유형자산양수결정")
                : detail("20260601000002");
        var result = client.getDisclosureBatch("00000001");
        assertThat(result.detailsIncomplete()).isTrue();
        assertThat(result.items().getFirst().details()).isNull();
    }
    @Test void detailsFailureDoesNotDiscardTitles() {
        response = uri -> uri.startsWith("/list.json") ? page(1, 1, 1, "유형자산양수결정")
                : "{\"status\":\"020\"}";
        var result = client.getDisclosureBatch("00000001");
        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.detailsIncomplete()).isTrue();
    }
    @Test void unknownNationalityDoesNotBecomeAnOverseasProject() {
        response = uri -> uri.startsWith("/list.json") ? page(1, 1, 1, "타법인주식및출자증권양수결정")
                : """
                {"status":"000","list":[{"corp_code":"00000001","rcept_no":"20260601000001",
                "iscmp_cmpnm":"테스트법인","iscmp_nt":"미상","inhdtl_inhprc":"100","inh_prd":"2026-09-30"}]}
                """;
        assertThat(client.getDisclosureBatch("00000001").items().getFirst().details().domain()).isEqualTo("INVESTMENT");
    }
    @Test void standaloneFallbackUsesOneReportsPriorAmountWithoutFetchingAnotherYear() {
        response = uri -> uri.contains("fs_div=CFS") ? "{\"status\":\"013\"}" : """
                {"status":"000","list":[{"account_id":"dart_ShortTermBorrowings","account_nm":"단기차입금",
                "sj_div":"BS","thstrm_amount":"150","frmtrm_amount":"100","currency":"KRW","rcept_no":"20260301000001"}]}
                """;
        var financials = client.getRecentFinancials("00000001");
        assertThat(financials.statementBasis()).isEqualTo("OFS");
        assertThat(financials.priorPeriodShortTermDebt()).isEqualByComparingTo("100");
        assertThat(requests).hasSize(2).allSatisfy(uri ->
                assertThat(uri).contains("/fnlttSinglAcntAll.json", "reprt_code=11011",
                        "bsns_year=" + java.time.LocalDate.now().getYear()));
    }
}

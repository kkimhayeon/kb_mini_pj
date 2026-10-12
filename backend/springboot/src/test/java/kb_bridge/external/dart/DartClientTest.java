package kb_bridge.external.dart;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DartClientTest {
    private HttpServer server;
    private DartClient client;
    private String body;
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (data.length == 0) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
            exchange.close();
        });
        server.start();
        client = new DartClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }
    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void identifiesDisclosuresMissingFromLaterPages() {
        body = """
                {"status":"000","total_count":101,"total_page":2,
                 "list":[{"report_nm":"시설투자 결정","rcept_dt":"20260601","rcept_no":"20260601000001"}]}
                """;
        var batch = client.getDisclosureBatch("00000001");
        assertThat(batch.truncated()).isTrue();
        assertThat(batch.items()).hasSize(1);
    }

    @Test
    void explicitNoDataIsNotAServiceFailure() {
        body = "{\"status\":\"013\"}";
        assertThat(client.getDisclosureBatch("00000001").items()).isEmpty();
        assertThat(client.getRecentFinancials("00000001")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"status\":\"000\"}", "{\"status\":\"020\",\"message\":\"limit\"}"})
    void malformedAndFailedResponsesDoNotBecomeEmptyData(String response) {
        body = response;
        assertThatThrownBy(() -> client.getDisclosureBatch("00000001")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.getRecentFinancials("00000001")).isInstanceOf(IllegalStateException.class);
    }
}

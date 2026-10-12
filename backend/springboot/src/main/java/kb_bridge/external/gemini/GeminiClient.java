package kb_bridge.external.gemini;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

@Component
public class GeminiClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public GeminiClient(
            @Value("${gemini.api.base-url}") String baseUrl,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.model:gemini-2.5-flash}") String model
    ) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public JsonNode generateInsight(String prompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("GEMINI_API_KEY must be configured to generate AI insights.");
        }

        Map<String, Object> request = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "reason", Map.of("type", "STRING"),
                                        "questions", Map.of(
                                                "type", "ARRAY",
                                                "items", Map.of("type", "STRING")
                                        )
                                ),
                                "required", List.of("reason", "questions")
                        )
                )
        );

        return restClient.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", apiKey)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }
}

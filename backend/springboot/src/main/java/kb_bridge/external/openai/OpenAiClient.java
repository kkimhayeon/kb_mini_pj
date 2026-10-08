package kb_bridge.external.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiClient(
            @Value("${openai.api.base-url}") String baseUrl,
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.api.model:gpt-4o-mini}") String model,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.apiKey = normalizeApiKey(apiKey);
        this.model = model;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public JsonNode generateInsight(String prompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("OPEN_API_KEY must be configured to generate OpenAI insights.");
        }

        Map<String, Object> request = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "Return only a JSON object with a string reason and a non-empty string array questions."
                        ),
                        Map.of("role", "user", "content", prompt)
                ),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "gap_insight",
                                "strict", true,
                                "schema", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "properties", Map.of(
                                                "reason", Map.of("type", "string"),
                                                "questions", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string")
                                                )
                                        ),
                                        "required", List.of("reason", "questions")
                                )
                        )
                )
        );

        String cacheKey = cacheKey(prompt);
        List<String> cachedResponses = jdbcTemplate.query(
                """
                        SELECT response_json::text
                        FROM ai_generation_cache
                        WHERE cache_key = ? AND expires_at > CURRENT_TIMESTAMP
                        """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                cacheKey
        );
        if (!cachedResponses.isEmpty()) {
            try {
                return objectMapper.readTree(cachedResponses.get(0));
            } catch (JacksonException exception) {
                throw new IllegalStateException("Cached OpenAI response contains invalid JSON.", exception);
            }
        }

        jdbcTemplate.update("DELETE FROM ai_generation_cache WHERE expires_at <= CURRENT_TIMESTAMP");
        JsonNode response = restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("OpenAI returned an empty response.");
        }
        validateResponse(response);
        try {
            jdbcTemplate.update("""
                    INSERT INTO ai_generation_cache (cache_key, model, response_json, expires_at)
                    VALUES (?, ?, ?::jsonb, ?)
                    ON CONFLICT (cache_key) DO UPDATE
                    SET model = EXCLUDED.model,
                        response_json = EXCLUDED.response_json,
                        created_at = CURRENT_TIMESTAMP,
                        expires_at = EXCLUDED.expires_at
                    """,
                    cacheKey,
                    model,
                    objectMapper.writeValueAsString(response),
                    Timestamp.from(Instant.now().plusSeconds(30L * 24 * 60 * 60))
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to cache OpenAI response.", exception);
        }
        return response;
    }

    private String cacheKey(String prompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("openai-chat-completions-json-schema-v3\n" + model + "\n" + prompt)
                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable for OpenAI cache keys.", exception);
        }
    }

    private void validateResponse(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        JsonNode refusal = message.path("refusal");
        if (refusal.isTextual() && !refusal.asText().isBlank()) {
            throw new IllegalStateException("OpenAI declined to generate the requested insight.");
        }
        JsonNode content = message.path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            String finishReason = response.path("choices").path(0).path("finish_reason").asText("unknown");
            throw new IllegalStateException("OpenAI returned no insight content (finish reason: "
                    + finishReason + ").");
        }
        try {
            JsonNode insight = objectMapper.readTree(content.asText());
            JsonNode questions = insight.path("questions");
            if (!insight.path("reason").isTextual()
                    || insight.path("reason").asText().isBlank()
                    || !questions.isArray()
                    || questions.isEmpty()
                    || !hasNonBlankQuestion(questions)) {
                throw new IllegalStateException("OpenAI returned an invalid insight response.");
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("OpenAI returned invalid JSON for its insight response.", exception);
        }
    }

    private boolean hasNonBlankQuestion(JsonNode questions) {
        for (JsonNode question : questions) {
            if (question.isTextual() && !question.asText().isBlank()) {
                return true;
            }
        }
        return false;
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
}

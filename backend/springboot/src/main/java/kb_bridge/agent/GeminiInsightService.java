package kb_bridge.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.external.gemini.GeminiClient;
import kb_bridge.rule.GapRuleEngine.Finding;

@Service
public class GeminiInsightService {

    public record Insight(String reason, List<String> questions) {
    }

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiInsightService(GeminiClient geminiClient, ObjectMapper objectMapper) {
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    public Optional<Insight> generate(Finding finding) {
        if (!geminiClient.isConfigured()) {
            return Optional.empty();
        }

        String prompt = createPrompt(finding);
        JsonNode response = geminiClient.generateInsight(prompt);
        JsonNode text = response == null
                ? null
                : response.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (text == null || !text.isTextual() || text.asText().isBlank()) {
            throw new IllegalStateException("Gemini returned no insight content.");
        }

        try {
            JsonNode result = objectMapper.readTree(text.asText());
            String reason = result.path("reason").asText("").trim();
            JsonNode questionNodes = result.path("questions");
            if (reason.isBlank() || !questionNodes.isArray()) {
                throw new IllegalStateException("Gemini returned an invalid insight response.");
            }

            List<String> questions = new java.util.ArrayList<>();
            questionNodes.forEach(question -> {
                if (question.isTextual() && !question.asText().isBlank() && questions.size() < 5) {
                    questions.add(question.asText().trim());
                }
            });
            if (questions.isEmpty()) {
                throw new IllegalStateException("Gemini returned no usable consultation questions.");
            }
            return Optional.of(new Insight(reason, questions));
        } catch (JacksonException e) {
            throw new IllegalStateException("Gemini returned invalid JSON for its insight response.", e);
        }
    }

    private String createPrompt(Finding finding) {
        List<EvidencePrompt> evidence = finding.evidence().stream()
                .map(item -> new EvidencePrompt(item.source(), item.title(), item.date()))
                .toList();
        PromptFacts facts = new PromptFacts(
                finding.type().name(),
                finding.existingInfo(),
                finding.latestInfo(),
                evidence
        );
        try {
            String serializedFacts = objectMapper.writeValueAsString(facts);
            return """
                    당신은 은행 기업금융 담당자를 돕는 보조자입니다.
                    아래 JSON은 규칙 엔진이 이미 판정한 Gap과 공개 근거입니다.
                    사실 판정이나 Gap 종류를 변경하지 말고, JSON에 없는 사실·수치·날짜·공시를 만들지 마세요.
                    JSON의 근거만으로 기존 정보와 최신 정보가 왜 다른지 한국어 한 문장으로 설명하세요.
                    고객에게 사실을 단정하지 않는 중립적인 확인 질문을 2~4개 작성하세요.
                    질문은 향후 계획을 고객에게 확인하는 형태로 작성하고, 확인되지 않은 사실을 전제로 삼지 마세요.
                    질문과 설명에는 'RM 정보', 'RM 기존 정보'라는 표현을 쓰지 말고 '기존 정보', '기존 상담 정보', '기존에 파악한 정보'라고 표현하세요.
                    근거가 부족하면 설명에 추가 확인이 필요하다고 밝히세요.
                    출력은 지정된 JSON 스키마만 사용하세요.

                    입력 사실:
                    %s
                    """.formatted(serializedFacts);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to prepare verified facts for Gemini.", e);
        }
    }

    private record PromptFacts(
            String gapType,
            String existingInfo,
            String latestInfo,
            List<EvidencePrompt> evidence
    ) {
    }

    private record EvidencePrompt(String source, String title, String date) {
    }
}

package kb_bridge.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.external.openai.OpenAiClient;
import kb_bridge.rule.GapRuleEngine.Finding;

@Service
public class OpenAiInsightService {

    public record Insight(String reason, List<String> questions) {
    }

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public OpenAiInsightService(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    public Optional<Insight> generate(Finding finding) {
        if (!openAiClient.isConfigured()) {
            return Optional.empty();
        }

        return parseInsight(openAiClient.generateInsight(createPrompt(finding)));
    }

    public Optional<Insight> generateDartOnly(
            Company company,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures
    ) {
        if (!openAiClient.isConfigured()) {
            return Optional.empty();
        }
        return parseInsight(openAiClient.generateInsight(createDartOnlyPrompt(company, financials, disclosures)));
    }

    public Optional<Insight> generateCompanyReference(String companyName) {
        if (!openAiClient.isConfigured()) {
            return Optional.empty();
        }
        String prompt = """
                당신은 기업 검색을 보조하는 참고 정보 도우미입니다.
                OpenDART에서 이 회사의 기업코드를 찾지 못했습니다. 이것은 회사가 존재하지 않는다는 뜻이 아닙니다.
                아래 회사명만으로 답하고, 회사 특정 사실을 확실히 알고 있지 않다면 추측하지 마세요.
                회사별 정보는 학습된 공개 지식일 수 있으며 최신성·정확성이 검증되지 않았다고 요약에 명시하세요.
                회사의 존재, 소재지, 대표자, 재무 수치, 사업 현황을 지어내지 마세요.
                회사에 대해 신뢰할 만한 지식이 부족하면 확인할 수 없다고 밝히고, 사용자가 확인할 공개 자료와
                조사 항목을 제안하세요. 상담 질문은 자료나 사업계획을 확인하는 중립적 질문 2~4개로 작성하세요.
                출력은 reason 문자열과 questions 문자열 배열을 포함하는 JSON 객체만 사용하세요.

                회사명: %s
                """.formatted(companyName);
        return parseInsight(openAiClient.generateInsight(prompt));
    }

    private Optional<Insight> parseInsight(JsonNode response) {
        JsonNode text = response == null
                ? null
                : response.path("choices").path(0).path("message").path("content");
        JsonNode refusal = response == null ? null : response.path("choices").path(0).path("message").path("refusal");
        if (refusal != null && refusal.isTextual() && !refusal.asText().isBlank()) {
            throw new IllegalStateException("OpenAI declined to generate the requested insight.");
        }
        if (text == null || !text.isTextual() || text.asText().isBlank()) {
            String finishReason = response == null
                    ? "no response"
                    : response.path("choices").path(0).path("finish_reason").asText("unknown");
            throw new IllegalStateException("OpenAI returned no insight content (finish reason: "
                    + finishReason + ").");
        }

        try {
            JsonNode result = objectMapper.readTree(text.asText());
            String reason = result.path("reason").asText("").trim();
            JsonNode questionNodes = result.path("questions");
            if (reason.isBlank() || !questionNodes.isArray()) {
                throw new IllegalStateException("OpenAI returned an invalid insight response.");
            }

            List<String> questions = new java.util.ArrayList<>();
            questionNodes.forEach(question -> {
                if (question.isTextual() && !question.asText().isBlank() && questions.size() < 5) {
                    questions.add(question.asText().trim());
                }
            });
            if (questions.isEmpty()) {
                throw new IllegalStateException("OpenAI returned no usable consultation questions.");
            }
            return Optional.of(new Insight(reason, questions));
        } catch (JacksonException e) {
            throw new IllegalStateException("OpenAI returned invalid JSON for its insight response.", e);
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
                    당신은 은행 기업금융 RM을 돕는 보조자입니다.
                    아래 JSON은 규칙 엔진이 이미 판정한 Gap과 공개 근거입니다.
                    사실 판정이나 Gap 종류를 변경하지 말고, JSON에 없는 사실·수치·날짜·공시를 만들지 마세요.
                    JSON의 근거만으로 기존 정보와 최신 정보가 왜 다른지 한국어 한 문장으로 설명하세요.
                    고객에게 사실을 단정하지 않는 중립적인 확인 질문을 2~4개 작성하세요.
                    질문은 향후 계획을 고객에게 확인하는 형태로 작성하고, 확인되지 않은 사실을 전제로 삼지 마세요.
                    근거가 부족하면 설명에 추가 확인이 필요하다고 밝히세요.
                    출력은 reason 문자열과 questions 문자열 배열을 포함하는 JSON 객체만 사용하세요.

                    입력 사실:
                    %s
                    """.formatted(serializedFacts);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to prepare verified facts for OpenAI.", e);
        }
    }

    private String createDartOnlyPrompt(
            Company company,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures
    ) {
        List<DartPromptEvidence> evidence = disclosures.stream()
                .limit(20)
                .map(item -> new DartPromptEvidence(
                        item.title(),
                        item.date(),
                        item.correctionStatus(),
                        item.excerpt()
                ))
                .toList();
        try {
            String serializedFacts = objectMapper.writeValueAsString(
                    new DartPromptFacts(company.companyName(), company.corpCode(), financials, evidence));
            return """
                    당신은 기업 공개정보를 요약하는 보조자입니다.
                    입력은 OpenDART에서 수집한 공개정보이며 RM 상담기록이나 내부 데이터베이스 정보는 포함되지 않았습니다.
                    입력에 없는 사실, 금액, 날짜, 공시를 만들지 마세요. 공시 제목만으로 계획이 확정되었다고 단정하지 마세요.
                    공개정보에서 확인할 수 있는 내용과 확인할 수 없는 내용을 구분해 한국어로 요약하고,
                    고객에게 확인할 중립적인 상담 질문을 2~4개 작성하세요.
                    자료가 비어 있거나 조회 범위가 제한적이면 이를 요약에 명시하세요.
                    출력은 reason 문자열과 questions 문자열 배열을 포함하는 JSON 객체만 사용하세요.

                    OpenDART 자료:
                    %s
                    """.formatted(serializedFacts);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to prepare OpenDART-only facts for OpenAI.", exception);
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

    private record DartPromptFacts(
            String companyName,
            String corpCode,
            FinancialSnapshot financials,
            List<DartPromptEvidence> disclosures
    ) {
    }

    private record DartPromptEvidence(
            String title,
            String date,
            String correctionStatus,
            String excerpt
    ) {
    }
}

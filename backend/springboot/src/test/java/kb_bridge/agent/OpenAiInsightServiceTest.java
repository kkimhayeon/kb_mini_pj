package kb_bridge.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.PlanKnowledge;
import kb_bridge.external.openai.OpenAiClient;
import kb_bridge.rule.GapRuleEngine.Finding;
import kb_bridge.rule.GapRuleEngine.GapType;

class OpenAiInsightServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsOpenAiWhenApiKeyIsNotConfigured() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(false);
        OpenAiInsightService service = new OpenAiInsightService(openAiClient, objectMapper);

        assertThat(service.generate(finding())).isEmpty();
        verify(openAiClient, never()).generateInsight(anyString());
    }

    @Test
    void parsesOpenAiExplanationAndQuestionsFromStructuredResponse() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.generateInsight(anyString())).thenReturn(response(
                "{\"reason\":\"기존 계획과 시설투자 공시가 달라 추가 확인이 필요합니다.\","
                        + "\"questions\":[\"투자 집행 시점은 언제입니까?\",\"필요 자금은 어느 정도입니까?\"]}"));
        OpenAiInsightService service = new OpenAiInsightService(openAiClient, objectMapper);

        var insight = service.generate(finding()).orElseThrow();

        assertThat(insight.reason()).contains("추가 확인이 필요");
        assertThat(insight.questions()).containsExactly("투자 집행 시점은 언제입니까?", "필요 자금은 어느 정도입니까?");
        verify(openAiClient).generateInsight(org.mockito.ArgumentMatchers.contains("시설투자 결정"));
    }

    @Test
    void generatesDartOnlyInsightWithoutIncludingRmPlanOrMemo() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.generateInsight(anyString())).thenReturn(response(
                "{\"reason\":\"공개정보에서 시설투자 공시를 확인했습니다.\",\"questions\":[\"투자 규모를 확인할 수 있습니까?\"]}"));
        OpenAiInsightService service = new OpenAiInsightService(openAiClient, objectMapper);
        Company company = new Company(
                "1",
                "00123456",
                "한빛전자",
                null,
                List.of(new PlanKnowledge(
                        "10", "INVESTMENT", "RM 전용 계획 메모", "EXPLICIT_YES", "PLANNED",
                        null, null, null, null, null, null, "NONE", null, null, null, "UNVERIFIED",
                        null, null)),
                "RM 기밀 메모"
        );

        var insight = service.generateDartOnly(
                company,
                new FinancialSnapshot("2025", null, null, null, null, null, null, null),
                List.of(finding().evidence().get(0))
        ).orElseThrow();

        assertThat(insight.reason()).contains("공개정보");
        verify(openAiClient).generateInsight(org.mockito.ArgumentMatchers.argThat(prompt ->
                prompt.contains("한빛전자")
                        && prompt.contains("OpenDART 자료")
                        && !prompt.contains("RM 기밀 메모")
                        && !prompt.contains("RM 전용 계획 메모")
        ));
    }

    @Test
    void rejectsResponsesThatDoNotFollowTheExpectedInsightSchema() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.isConfigured()).thenReturn(true);
        when(openAiClient.generateInsight(anyString())).thenReturn(response("{\"reason\":\"설명만 있음\"}"));
        OpenAiInsightService service = new OpenAiInsightService(openAiClient, objectMapper);

        assertThatThrownBy(() -> service.generate(finding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid insight response");
    }

    private Finding finding() {
        return new Finding(
                GapType.INVESTMENT_PLAN_GAP,
                "없음",
                "시설투자 관련 공시 확인",
                List.of(new DisclosureEvidence(
                        "OpenDART", "시설투자 결정", "2026-06-01", "https://dart.fss.or.kr",
                        "20260601000001", "NOT_CORRECTION", "시설투자 결정", "시설투자 결정")),
                "INVESTMENT",
                "investment-1",
                "CONTRADICTION",
                "PRIORITY_1"
        );
    }

    private tools.jackson.databind.JsonNode response(String text) {
        return objectMapper.valueToTree(
                new Response(List.of(new Candidate(new Message(text)))));
    }

    private record Response(List<Candidate> choices) {
    }

    private record Candidate(Message message) {
    }

    private record Message(String content) {
    }
}

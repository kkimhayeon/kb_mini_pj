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
import kb_bridge.external.gemini.GeminiClient;
import kb_bridge.rule.GapRuleEngine.Finding;
import kb_bridge.rule.GapRuleEngine.GapType;

class GeminiInsightServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsGeminiWhenApiKeyIsNotConfigured() {
        GeminiClient geminiClient = mock(GeminiClient.class);
        when(geminiClient.isConfigured()).thenReturn(false);
        GeminiInsightService service = new GeminiInsightService(geminiClient, objectMapper);

        assertThat(service.generate(finding())).isEmpty();
        verify(geminiClient, never()).generateInsight(anyString());
    }

    @Test
    void parsesGeminiExplanationAndQuestionsFromStructuredResponse() {
        GeminiClient geminiClient = mock(GeminiClient.class);
        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateInsight(anyString())).thenReturn(objectMapper.valueToTree(
                new Response(List.of(new Candidate(new Content(List.of(new Part(
                        """
                        {"reason":"기존 계획과 시설투자 공시가 달라 추가 확인이 필요합니다.","questions":["투자 집행 시점은 언제입니까?","필요 자금은 어느 정도입니까?"]}
                        """))))))));
        GeminiInsightService service = new GeminiInsightService(geminiClient, objectMapper);

        var insight = service.generate(finding()).orElseThrow();

        assertThat(insight.reason()).contains("추가 확인이 필요");
        assertThat(insight.questions()).containsExactly("투자 집행 시점은 언제입니까?", "필요 자금은 어느 정도입니까?");
        verify(geminiClient).generateInsight(org.mockito.ArgumentMatchers.contains("시설투자 결정"));
    }

    @Test
    void rejectsResponsesThatDoNotFollowTheExpectedInsightSchema() {
        GeminiClient geminiClient = mock(GeminiClient.class);
        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateInsight(anyString())).thenReturn(objectMapper.valueToTree(
                new Response(List.of(new Candidate(new Content(List.of(new Part("{\"reason\":\"설명만 있음\"}"))))))));
        GeminiInsightService service = new GeminiInsightService(geminiClient, objectMapper);

        assertThatThrownBy(() -> service.generate(finding()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid insight response");
    }

    private Finding finding() {
        return new Finding(
                GapType.INVESTMENT_PLAN_GAP,
                kb_bridge.rule.GapRuleEngine.ChangeType.NEEDS_CONFIRMATION,
                "없음",
                "시설투자 관련 공시 확인",
                List.of(new DisclosureEvidence("OpenDART", "시설투자 결정", "2026-06-01", "https://dart.fss.or.kr"))
        );
    }

    private record Response(List<Candidate> candidates) {
    }

    private record Candidate(Content content) {
    }

    private record Content(List<Part> parts) {
    }

    private record Part(String text) {
    }
}

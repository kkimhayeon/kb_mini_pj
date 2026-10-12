package kb_bridge.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import kb_bridge.agent.*;
import kb_bridge.domain.company.entity.*;
import kb_bridge.domain.company.entity.GapAnalysisResponse.RetrievalState;
import kb_bridge.domain.company.persistence.PlanFactRepository;
import kb_bridge.external.dart.DartClient;
import kb_bridge.external.dart.DartClient.DisclosureBatch;
import kb_bridge.external.dart.dto.DartCompanyResponse;
import kb_bridge.rule.GapRuleEngine;

class CompanyServiceTest {
    private final DartClient dart = mock(DartClient.class);
    private final RmKnowledgeService knowledge = mock(RmKnowledgeService.class);
    private final GeminiInsightService insights = mock(GeminiInsightService.class);
    private final GapAnalysisPersistenceService persistence = mock(GapAnalysisPersistenceService.class);
    private final PlanFactRepository facts = mock(PlanFactRepository.class);
    private final GapAgent agent = new GapAgent(new GapRuleEngine(), insights);
    private final CompanyService service = new CompanyService(dart, knowledge, agent, persistence, facts);
    private Company company;
    private final FinancialSnapshot financials = new FinancialSnapshot("2025", BigDecimal.TEN,
            BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

    @BeforeEach
    void prepare() {
        company = new Company("1", "00000001", "테스트기업", LocalDate.parse("2026-01-01"),
                "없음", "없음", "없음", "", List.of());
        when(knowledge.findById("1")).thenReturn(company);
        var details = mock(DartCompanyResponse.class);
        when(details.status()).thenReturn("000");
        when(dart.getCompany("00000001")).thenReturn(details);
        when(dart.getRecentFinancials("00000001")).thenReturn(financials);
        when(dart.getDisclosureBatch("00000001")).thenReturn(batch("현금배당 결정", false));
    }

    @Test
    void completeLookupWithoutMatchesMeansNoEvidenceWithinScope() {
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("NO_CHANGE_EVIDENCE");
        assertThat(result.scopeLimited()).isFalse();
        assertThat(result.retrievalStatus()).isEqualTo("SUCCESS");
        verify(persistence).save("1", result, List.of());
    }

    @Test
    void keepsDisclosureFindingsWhenFinancialLookupFails() {
        when(dart.getRecentFinancials("00000001")).thenThrow(new ResourceAccessException("secret upstream URL"));
        when(dart.getDisclosureBatch("00000001")).thenReturn(batch("신규 시설투자 결정", false));
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("GAPS_FOUND");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.scopeLimited()).isTrue();
        assertThat(result.retrievalStatus()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.sources().toString()).doesNotContain("secret upstream URL");
    }

    @Test
    void failedLookupsNeverLookLikeNoChanges() {
        when(dart.getRecentFinancials("00000001")).thenThrow(new ResourceAccessException("timeout"));
        when(dart.getDisclosureBatch("00000001")).thenThrow(new IllegalStateException("unavailable"));
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(result.retrievalStatus()).isEqualTo("FAILURE");
        assertThat(result.gaps()).isEmpty();
    }

    @Test
    void successfulButEmptyLookupsAreDistinctFromFailures() {
        when(dart.getRecentFinancials("00000001")).thenReturn(null);
        when(dart.getDisclosureBatch("00000001")).thenReturn(new DisclosureBatch(List.of(), false));
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("NO_DATA");
        assertThat(result.retrievalStatus()).isEqualTo("SUCCESS");
        assertThat(result.scopeLimited()).isTrue();
    }

    @Test
    void doesNotClaimFullCoverageForTruncatedDisclosuresOrMissingFinancialFields() {
        when(dart.getDisclosureBatch("00000001")).thenReturn(batch("현금배당 결정", true));
        when(dart.getRecentFinancials("00000001")).thenReturn(
                new FinancialSnapshot("2025", BigDecimal.TEN, null, null, null, null));
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(result.sources()).filteredOn(item -> !"COMPANY".equals(item.source()))
                .allMatch(item -> item.status() == RetrievalState.PARTIAL);
    }

    @Test
    void skippedFinancialLookupHasAnExplicitReason() {
        var known = new Company("1", "00000001", "테스트기업", company.consultationDate(),
                "없음", "회사채 발행 예정", "없음", "", List.of());
        when(knowledge.findById("1")).thenReturn(known);
        var result = service.analyzeCompany("1");
        assertThat(result.sources()).anyMatch(item -> "FINANCIALS".equals(item.source())
                && item.status() == RetrievalState.NOT_REQUESTED);
        assertThat(result.scopeLimited()).isFalse();
        verify(dart, never()).getRecentFinancials(anyString());
    }

    @Test
    void companyLookupFailureIsPersistedWithoutRunningOtherLookups() {
        when(dart.getCompany("00000001")).thenReturn(null);
        var result = service.analyzeCompany("1");
        assertThat(result.outcome()).isEqualTo("INCONCLUSIVE");
        assertThat(result.retrievalStatus()).isEqualTo("FAILURE");
        verify(dart, never()).getRecentFinancials(anyString());
        verify(dart, never()).getDisclosureBatch(anyString());
        verify(persistence).save("1", result, List.of());
    }

    @Test
    void geminiFailureKeepsFactsRulesQuestionsAndSuccessfulRetrievalStatus() {
        when(dart.getDisclosureBatch("00000001")).thenReturn(batch("신규 시설투자 결정", false));
        when(insights.generate(any())).thenThrow(new ResourceAccessException("timeout"));
        var result = service.analyzeCompany("1");
        assertThat(result.gaps()).hasSize(1);
        var gap = result.gaps().getFirst();
        assertThat(gap.changeType()).isEqualTo("CONFLICT");
        assertThat(gap.explanationSource()).isEqualTo("Rule fallback (Gemini failed)");
        assertThat(gap.evidence()).containsExactly(batch("신규 시설투자 결정", false).items().getFirst());
        assertThat(gap.questions()).isNotEmpty();
        assertThat(result.retrievalStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void badGeminiJsonAlsoFallsBack() {
        when(dart.getDisclosureBatch("00000001")).thenReturn(batch("신규 시설투자 결정", false));
        when(insights.generate(any())).thenThrow(new IllegalStateException("invalid JSON"));
        assertThat(service.analyzeCompany("1").gaps().getFirst().explanationSource())
                .isEqualTo("Rule fallback (Gemini failed)");
    }

    private DisclosureBatch batch(String title, boolean truncated) {
        return new DisclosureBatch(List.of(new DisclosureEvidence("OpenDART", title, "2026-06-01", "https://dart.fss.or.kr")), truncated);
    }
}

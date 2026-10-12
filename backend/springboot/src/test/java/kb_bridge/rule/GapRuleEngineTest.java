package kb_bridge.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import kb_bridge.agent.GapAgent;
import kb_bridge.agent.GeminiInsightService;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.rule.GapRuleEngine.ChangeType;
import kb_bridge.rule.GapRuleEngine.Finding;
import kb_bridge.rule.GapRuleEngine.GapType;

class GapRuleEngineTest {

    private final GapRuleEngine engine = new GapRuleEngine();

    @ParameterizedTest
    @CsvSource({
            "신규 시설투자 결정, INVESTMENT_PLAN_GAP",
            "회사채 발행 결정, FUNDING_PLAN_GAP",
            "해외법인 설립 및 출자, FX_BUSINESS_GAP"
    })
    void onlyMatchesTheRelevantDomain(String title, GapType expected) {
        assertThat(detect(company("없음", "없음", "없음"), title))
                .extracting(Finding::type).containsExactly(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"현금배당 결정", "주식매수선택권 부여 결정", "증권 발행 결과", "자기주식 취득 결정", "매출 증가"})
    void genericActionWordsDoNotCreateGaps(String title) {
        assertThat(detect(company("없음", "없음", "없음"), title)).isEmpty();
    }

    @Test
    void domesticAcquisitionIsNotAnOverseasBusinessSignal() {
        assertThat(detect(company("없음", "없음", "없음"), "국내 타법인 주식 취득 결정"))
                .extracting(Finding::type).containsExactly(GapType.INVESTMENT_PLAN_GAP);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingInformationIsUnconfirmedRatherThanNoPlan(String missing) {
        var findings = detect(company(missing, "있음", "있음"), "신규 시설투자 결정");
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().existingInfo()).isEqualTo("기존 정보 없음");
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.NEEDS_CONFIRMATION);
    }

    @Test
    void explicitNoPlanCanConflictWithAnInvestmentDisclosure() {
        var findings = detect(company("계획 없음", "있음", "있음"), "신규 시설투자 결정");
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().existingInfo()).isEqualTo("계획 없음");
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.CONFLICT);
    }

    @ParameterizedTest
    @CsvSource({
            "시설투자 계획, 신규 시설투자 결정 취소, INVESTMENT_PLAN_GAP",
            "회사채 발행 예정, 회사채 발행 결정 철회, FUNDING_PLAN_GAP",
            "해외법인 출자 검토, 해외법인 출자 철회, FX_BUSINESS_GAP",
            "회사채 발행 계획, 회사채 상환완료, FUNDING_PLAN_GAP"
    })
    void relatedCancellationIsAConfirmationCandidate(String plan, String title, GapType domain) {
        Company company = company(
                domain == GapType.INVESTMENT_PLAN_GAP ? plan : "없음",
                domain == GapType.FUNDING_PLAN_GAP ? plan : "없음",
                domain == GapType.FX_BUSINESS_GAP ? plan : "없음");
        var findings = detect(company, title);
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().type()).isEqualTo(domain);
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.NEEDS_CONFIRMATION);
        assertThat(findings.getFirst().latestInfo()).contains("취소·철회·종료", "확인");
        assertThat(findings.getFirst().evidence()).containsExactly(evidence(title));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"없음", "유상증자 예정", "계획 있음"})
    void cancellationWithoutARelatedExistingPlanDoesNotCreateAConflict(String funding) {
        assertThat(detect(company("없음", funding, "없음"), "회사채 발행 결정 취소")).isEmpty();
    }

    @Test
    void cancellationAndOriginalDisclosureAreNotReportedAsConfirmedIssuance() {
        var findings = engine.detect(company("없음", "회사채 발행 예정", "없음"), null,
                List.of(evidence("회사채 발행 결정"), evidence("회사채 발행 결정 취소")));
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.NEEDS_CONFIRMATION);
        assertThat(findings.getFirst().evidence()).hasSize(2);
    }

    @Test
    void materialDebtIncreaseConflictsWithExplicitNoFundingPlan() {
        var findings = engine.detect(company("없음", "없음", "없음"), financials("125", "100"), List.of());
        assertThat(findings).extracting(Finding::type).containsExactly(GapType.FUNDING_PLAN_GAP);
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.CONFLICT);
    }

    @Test
    void materialDebtIncreaseWithMissingInformationNeedsConfirmation() {
        var findings = engine.detect(company("없음", null, "없음"), financials("125", "100"), List.of());
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().changeType()).isEqualTo(ChangeType.NEEDS_CONFIRMATION);
        assertThat(findings.getFirst().existingInfo()).isEqualTo("기존 정보 없음");
        assertThat(findings.getFirst().latestInfo()).doesNotContain("계획이 없지만");
    }

    @Test
    void doesNotFlagDebtBelowTheExistingThreshold() {
        assertThat(engine.detect(company("없음", "없음", "없음"), financials("119", "100"), List.of()))
                .isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"없음", "미확인"})
    void stillFetchesFinancialsForMissingOrExplicitlyAbsentPlans(String funding) {
        var company = company("있음", funding, "있음");
        assertThat(engine.needsFinancialStatements(company)).isTrue();
        assertThat(engine.needsDisclosures(company)).isTrue();
    }

    @Test
    void agentKeepsMissingInformationPending() {
        var insights = mock(GeminiInsightService.class);
        org.mockito.Mockito.when(insights.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        var agent = new GapAgent(engine, insights);
        var result = agent.analyze(company(null, "있음", "있음"), "00000001", null,
                List.of(evidence("시설투자 결정")));
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().getFirst().changeType()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(result.gaps().getFirst().assessmentStatus()).isEqualTo("PENDING");
    }

    private List<Finding> detect(Company company, String title) {
        return engine.detect(company, null, List.of(evidence(title)));
    }

    private FinancialSnapshot financials(String current, String previous) {
        return new FinancialSnapshot("2025", null, null, new BigDecimal(current), new BigDecimal(previous), null);
    }

    private Company company(String investment, String funding, String foreignBusiness) {
        return new Company("1", "테스트기업", LocalDate.parse("2026-01-01"),
                investment, funding, foreignBusiness, "");
    }

    private DisclosureEvidence evidence(String title) {
        return new DisclosureEvidence("OpenDART", title, "2026-06-01", "https://dart.fss.or.kr");
    }
}

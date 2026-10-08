package kb_bridge.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import kb_bridge.agent.GapAgent;
import kb_bridge.agent.OpenAiInsightService;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.PlanKnowledge;
import kb_bridge.rule.GapRuleEngine.GapType;

class GapRuleEngineTest {

    private final GapRuleEngine gapRuleEngine = new GapRuleEngine();

    @Test
    void classifiesDirectDisclosureAgainstExplicitlyAbsentPlanAsContradiction() {
        Company company = company("없음", "차입 검토", "없음");
        var evaluation = evaluate(company, List.of(evidence(
                "주요사항보고서(시설투자 결정)",
                "회사는 생산설비 시설투자를 결정하였습니다.")));

        assertThat(evaluation.findings()).extracting(GapRuleEngine.Finding::type)
                .contains(GapType.INVESTMENT_PLAN_GAP);
        assertThat(evaluation.assessments()).filteredOn(a -> "INVESTMENT".equals(a.domain()))
                .singleElement()
                .extracting(a -> a.changeType())
                .isEqualTo("CONTRADICTION");
    }

    @Test
    void doesNotConfirmChangeFromDisclosureTitleAlone() {
        var evaluation = evaluate(company("없음", "차입 검토", "없음"),
                List.of(evidence("시설투자 결정", "")));

        assertThat(evaluation.findings()).isEmpty();
        assertThat(evaluation.assessments()).filteredOn(a -> "INVESTMENT".equals(a.domain()))
                .singleElement()
                .extracting(a -> a.status())
                .isEqualTo("JUDGMENT_PENDING");
    }

    @Test
    void treatsDirectEvidenceForUnrecordedPlanAsNewInformation() {
        var company = company(null, "차입 검토", "없음");
        var evaluation = evaluate(company, List.of(evidence(
                "주요사항보고서(공장 신설 투자결정)",
                "신규 공장 신설 투자를 결정하였습니다.")));

        assertThat(evaluation.assessments()).filteredOn(a -> "INVESTMENT".equals(a.domain()))
                .singleElement()
                .extracting(a -> a.changeType())
                .isEqualTo("NEW_INFORMATION");
    }

    @Test
    void overdueReviewRequiresReconfirmationButDoesNotConfirmAChange() {
        Company company = company("생산설비 투자 검토", "차입 검토", "없음");
        var oldPlan = new PlanKnowledge(
                "investment-1", "INVESTMENT", "생산설비 투자 검토", "EXPLICIT_YES", "UNDER_REVIEW",
                LocalDate.parse("2026-01-01"), null, null, null, null, "mock", "NONE", null,
                LocalDate.parse("2026-01-31"), null, "UNVERIFIED", null, null);
        company = withPlans(company, List.of(oldPlan));

        var evaluation = gapRuleEngine.evaluate(company, null, List.of(),
                LocalDate.parse("2026-06-01"), "SUCCESS");

        assertThat(evaluation.findings()).isEmpty();
        assertThat(evaluation.assessments()).singleElement()
                .extracting(a -> a.changeType())
                .isEqualTo("RECONFIRMATION");
    }

    @Test
    void unresolvedCorrectionIsJudgmentPending() {
        var evaluation = evaluate(company("없음", "차입 검토", "없음"),
                List.of(new DisclosureEvidence(
                        "OpenDART", "[기재정정] 시설투자 결정", "2026-06-01",
                        "https://dart.fss.or.kr", "20260601000001", "UNRESOLVED",
                        "회사는 시설투자를 결정하였습니다.", "회사는 시설투자를 결정하였습니다.")));

        assertThat(evaluation.assessments()).filteredOn(a -> "INVESTMENT".equals(a.domain()))
                .singleElement()
                .extracting(a -> a.changeType())
                .isEqualTo("ADDITIONAL_CONFIRMATION_REQUIRED");
    }

    @Test
    void debtIncreaseIsAnInvestigationSignalNotAConfirmedGap() {
        Company company = company("생산설비 투자 검토", "없음", "해외사업 검토");
        FinancialSnapshot financials = new FinancialSnapshot(
                "2025", null, null, new BigDecimal("125"), new BigDecimal("100"),
                new BigDecimal("125"), new BigDecimal("100"), null);

        var evaluation = gapRuleEngine.evaluate(company, financials, List.of(),
                LocalDate.parse("2026-06-01"), "SUCCESS");

        assertThat(evaluation.findings()).isEmpty();
        assertThat(evaluation.financialSignals()).singleElement()
                .extracting(signal -> signal.status())
                .isEqualTo("INVESTIGATION_CANDIDATE");
    }

    @Test
    void reportsNoEvidenceWithoutClaimingExistingKnowledgeIsStillValid() {
        var evaluation = evaluate(company("없음", "차입 검토", "없음"), List.of());

        assertThat(evaluation.assessments()).allMatch(
                assessment -> "CHANGE_EVIDENCE_NOT_FOUND".equals(assessment.status()));
        assertThat(evaluation.findings()).isEmpty();
    }

    @Test
    void reportsFailedDisclosureCollectionWithoutCallingItNoEvidence() {
        var company = company("없음", "차입 검토", "없음");
        var evaluation = gapRuleEngine.evaluate(
                company, null, List.of(), LocalDate.parse("2026-06-30"), "FAILED");

        assertThat(evaluation.assessments()).allMatch(
                assessment -> "RETRIEVAL_FAILED".equals(assessment.status()));
        assertThat(evaluation.assessments()).noneMatch(
                assessment -> "CHANGE_EVIDENCE_NOT_FOUND".equals(assessment.status()));
        assertThat(evaluation.findings()).isEmpty();
    }

    @Test
    void treatsDateOnlySameDayEvidenceAsPending() {
        var company = company("없음", "차입 검토", "없음");
        var sameDay = new PlanKnowledge(
                "investment-1", "INVESTMENT", null, "EXPLICIT_NO", "NOT_APPLICABLE",
                LocalDate.parse("2026-06-01"), null, null, null, null, "mock", "NONE", null,
                null, null, "UNVERIFIED", null, null);
        company = withPlans(company, List.of(sameDay));

        var evaluation = gapRuleEngine.evaluate(
                company,
                null,
                List.of(evidence("시설투자 결정", "시설투자를 결정하였습니다.")),
                LocalDate.parse("2026-06-01"),
                "SUCCESS");

        assertThat(evaluation.assessments()).singleElement()
                .extracting(a -> a.status())
                .isEqualTo("JUDGMENT_PENDING");
        assertThat(evaluation.findings()).isEmpty();
    }

    @Test
    void plansRelevantResearchForPlanRows() {
        GapAgent agent = new GapAgent(gapRuleEngine, mock(OpenAiInsightService.class));
        GapAgent.ResearchPlan plan = agent.planResearch(company("없음", "차입 검토", "없음"));

        assertThat(plan.financialStatements()).isTrue();
        assertThat(plan.disclosures()).isTrue();
    }

    private GapRuleEngine.Evaluation evaluate(Company company, List<DisclosureEvidence> disclosures) {
        return gapRuleEngine.evaluate(
                company, null, disclosures, LocalDate.parse("2026-06-30"), "SUCCESS");
    }

    private Company company(String investment, String funding, String foreignBusiness) {
        return withPlans(new Company("1", "00123456", "테스트기업", LocalDate.parse("2026-01-01"),
                List.of(), "가상 메모"), List.of(
                        plan("INVESTMENT", "investment-1", investment),
                        plan("FUNDING", "funding-1", funding),
                        plan("FOREIGN_BUSINESS", "foreign_business-1", foreignBusiness)));
    }

    private Company withPlans(Company company, List<PlanKnowledge> plans) {
        return new Company(company.companyId(), company.corpCode(), company.companyName(),
                company.consultationDate(), plans, company.rmMemo());
    }

    private PlanKnowledge plan(String domain, String planId, String scope) {
        String status = scope == null ? "UNRECORDED" : "없음".equals(scope) ? "EXPLICIT_NO" : "EXPLICIT_YES";
        String planStatus = "EXPLICIT_NO".equals(status) ? "NOT_APPLICABLE"
                : "UNRECORDED".equals(status) ? "UNKNOWN" : "PLANNED";
        return new PlanKnowledge(planId, domain, "EXPLICIT_NO".equals(status) ? null : scope, status, planStatus,
                LocalDate.parse("2026-01-01"), null, null, null, null,
                "가상 RM 상담", "NONE", null, null, null, "UNVERIFIED", null, null);
    }

    private DisclosureEvidence evidence(String title, String content) {
        return new DisclosureEvidence(
                "OpenDART", title, "2026-06-01", "https://dart.fss.or.kr/example",
                "20260601000001", "NOT_CORRECTION", content, content);
    }
}

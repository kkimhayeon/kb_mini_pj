package kb_bridge.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import kb_bridge.agent.GapAgent;
import kb_bridge.agent.GeminiInsightService;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.rule.GapRuleEngine.GapType;

class GapRuleEngineTest {

    private final GapRuleEngine gapRuleEngine = new GapRuleEngine();

    @Test
    void detectsInvestmentPlanGapFromFacilityInvestmentDisclosure() {
        Company company = company("없음", "있음", "있음");
        List<GapRuleEngine.Finding> findings = gapRuleEngine.detect(
                company,
                null,
                List.of(evidence("신규 시설투자 결정"))
        );

        assertThat(findings).extracting(GapRuleEngine.Finding::type)
                .contains(GapType.INVESTMENT_PLAN_GAP);
    }

    @Test
    void detectsFundingPlanGapFromBondIssuanceDisclosure() {
        Company company = company("있음", "", "있음");
        List<GapRuleEngine.Finding> findings = gapRuleEngine.detect(
                company,
                null,
                List.of(evidence("회사채 발행 결정"))
        );

        assertThat(findings).extracting(GapRuleEngine.Finding::type)
                .contains(GapType.FUNDING_PLAN_GAP);
    }

    @Test
    void ignoresCancelledBondIssuanceDisclosure() {
        Company company = company("있음", "", "있음");
        List<GapRuleEngine.Finding> findings = gapRuleEngine.detect(
                company,
                null,
                List.of(evidence("회사채 발행 결정 취소"))
        );

        assertThat(findings).isEmpty();
    }

    @Test
    void detectsFundingPlanGapFromMaterialShortTermDebtIncrease() {
        Company company = company("있음", "", "있음");
        FinancialSnapshot financials = new FinancialSnapshot(
                "2025", null, null, new BigDecimal("125"), new BigDecimal("100"), null);

        List<GapRuleEngine.Finding> findings = gapRuleEngine.detect(company, financials, List.of());

        assertThat(findings).extracting(GapRuleEngine.Finding::type)
                .contains(GapType.FUNDING_PLAN_GAP);
    }

    @Test
    void detectsForeignBusinessGapFromOverseasBusinessDisclosure() {
        Company company = company("있음", "있음", "");
        List<GapRuleEngine.Finding> findings = gapRuleEngine.detect(
                company,
                null,
                List.of(evidence("해외법인 설립 및 출자"))
        );

        assertThat(findings).extracting(GapRuleEngine.Finding::type)
                .contains(GapType.FX_BUSINESS_GAP);
    }

    @Test
    void plansDisclosuresForEveryAnalysisAndFinancialsWhenFundingPlanIsMissing() {
        GapAgent agent = new GapAgent(gapRuleEngine, mock(GeminiInsightService.class));
        GapAgent.ResearchPlan plan = agent.planResearch(company("있음", "", "있음"));

        assertThat(plan.financialStatements()).isTrue();
        assertThat(plan.disclosures()).isTrue();
    }

    @Test
    void treatsBlankRmInformationAsNoPlanForSeedData() {
        Company company = company(null, null, null);

        assertThat(gapRuleEngine.needsFinancialStatements(company)).isTrue();
        assertThat(gapRuleEngine.needsDisclosures(company)).isTrue();
    }

    private Company company(String investment, String funding, String foreignBusiness) {
        return new Company("1", "테스트기업", LocalDate.parse("2026-01-01"),
                investment, funding, foreignBusiness, "");
    }

    private DisclosureEvidence evidence(String title) {
        return new DisclosureEvidence("OpenDART", title, "2026-06-01", "https://dart.fss.or.kr");
    }
}
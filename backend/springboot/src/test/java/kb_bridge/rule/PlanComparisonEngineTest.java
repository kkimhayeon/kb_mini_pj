package kb_bridge.rule;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import kb_bridge.domain.company.entity.*;

class PlanComparisonEngineTest {
    private Company.PlanSummary plan(long id, String name, String currency, String precision) {
        return new Company.PlanSummary(id, "INVESTMENT", name, "PLANNED", name, new BigDecimal("100"),
                currency, LocalDate.parse("2026-03-01"), precision, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }
    private Company company(Company.PlanSummary... plans) {
        return new Company("1", "00000001", "테스트", LocalDate.parse("2026-01-01"),
                "시설 투자 예정", "있음", "있음", "", List.of(plans));
    }
    private DisclosureEvidence evidence(String name, String amount, String date) {
        return new DisclosureEvidence("OpenDART", "유형자산 양수 결정", "2026-02-01",
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260201000001",
                new DisclosureEvidence.Details("INVESTMENT", name, new BigDecimal(amount), "KRW",
                        LocalDate.parse(date), "DAY", "20260201000001", "structured test"));
    }
    @Test void differencesStayLinkedToTheCorrectProject() {
        var company = company(plan(1, "서울물류센터", "KRW", "DAY"), plan(2, "부산물류센터", "KRW", "DAY"));
        var findings = new GapRuleEngine().detect(company, null, List.of(evidence("부산 물류센터", "150", "2026-04-01")));
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().planId()).isEqualTo(2L);
        assertThat(findings.getFirst().changes()).extracting(PlanComparison.FieldChange::field)
                .containsExactly("AMOUNT", "EXPECTED_AT");
        assertThat(findings.getFirst().changeType()).isEqualTo(GapRuleEngine.ChangeType.NEEDS_CONFIRMATION);
    }
    @ParameterizedTest @CsvSource({
        "DAY,2026-03-01", "MONTH,2026-03-31", "QUARTER,2026-01-31", "HALF,2026-06-30", "YEAR,2026-12-31"
    })
    void equalAmountsAndDatesWithinTheConfirmedPeriodDoNotCreateGaps(String precision, String date) {
        var company = company(plan(1, "서울물류센터", "KRW", precision));
        var evidence = List.of(evidence("서울물류센터", "100.00", date));
        assertThat(PlanComparisonEngine.compare(company, evidence).getFirst().comparisonStatus()).isEqualTo("NO_DIFFERENCE");
        assertThat(new GapRuleEngine().detect(company, null, evidence)).isEmpty();
    }
    @Test void neverConvertsCurrenciesImplicitly() {
        var comparison = PlanComparisonEngine.compare(company(plan(1, "서울물류센터", "USD", "DAY")),
                List.of(evidence("서울물류센터", "150", "2026-03-01"))).getFirst();
        assertThat(comparison.changes()).isEmpty();
        assertThat(comparison.comparisonStatus()).isEqualTo("INSUFFICIENT");
    }
    @Test void duplicateNamesAreNotArbitrarilyLinked() {
        var comparison = PlanComparisonEngine.compare(company(plan(1, "서울물류센터", "KRW", "DAY"),
                plan(2, "서울물류센터", "KRW", "DAY")), List.of(evidence("서울물류센터", "150", "2026-04-01"))).getFirst();
        assertThat(comparison.matchStatus()).isEqualTo("AMBIGUOUS");
        assertThat(comparison.planId()).isNull();
        assertThat(comparison.changes()).isEmpty();
    }
    @Test void genericOrOnlySimilarNamesAreNotProjectMatches() {
        for (String name : List.of("서울물류센터 증설", "부동산")) {
            var comparison = PlanComparisonEngine.compare(company(plan(1, "서울물류센터", "KRW", "DAY"),
                    plan(2, "부동산", "KRW", "DAY")), List.of(evidence(name, "150", "2026-04-01"))).getFirst();
            assertThat(comparison.matchStatus()).isEqualTo("UNMATCHED");
            assertThat(comparison.changes()).isEmpty();
        }
    }
    @Test void disclosuresBeforeTheConfirmedSnapshotAreHistorical() {
        var old = evidence("서울물류센터", "150", "2026-04-01");
        old = new DisclosureEvidence(old.source(), old.title(), "2025-12-01", old.url(), old.details());
        var company = company(plan(1, "서울물류센터", "KRW", "DAY"));
        assertThat(PlanComparisonEngine.compare(company, List.of(old)).getFirst().comparisonStatus()).isEqualTo("HISTORICAL");
        assertThat(new GapRuleEngine().detect(company, null, List.of(old))).isEmpty();
    }
    @Test void titlesAloneDoNotYieldNumericComparisons() {
        assertThat(PlanComparisonEngine.compare(company(plan(1, "서울물류센터", "KRW", "DAY")),
                List.of(new DisclosureEvidence("OpenDART", "서울물류센터 150억원 투자 결정", "2026-02-01", null)))).isEmpty();
    }
    @Test void multipleProjectsInOneDomainProduceSeparateFindings() {
        var findings = new GapRuleEngine().detect(company(plan(1, "서울물류센터", "KRW", "DAY"),
                plan(2, "부산물류센터", "KRW", "DAY")), null,
                List.of(evidence("서울물류센터", "120", "2026-03-01"), evidence("부산물류센터", "130", "2026-03-01")));
        assertThat(findings).extracting(GapRuleEngine.Finding::planId).containsExactly(1L, 2L);
    }
}

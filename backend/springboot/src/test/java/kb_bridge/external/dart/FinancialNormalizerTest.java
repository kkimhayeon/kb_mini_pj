package kb_bridge.external.dart;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import kb_bridge.external.dart.dto.DartFinancialResponse.Item;

class FinancialNormalizerTest {
    private Item row(String name, String id, String section, String current, String prior, String currency) {
        return new Item(name, current, "2025", prior, id, section, currency, "20260301000001");
    }
    @Test void comparesCurrentAndPriorDebtFromTheSameStatementRow() {
        var snapshot = FinancialNormalizer.normalize(2025, "CFS", List.of(
                row("단기차입금", "dart_ShortTermBorrowings", "BS", "1,500", "1,000", "KRW"),
                row("영업현금흐름", "ifrs-full_CashFlowsFromUsedInOperatingActivities", "CF", "(250)", "500", "KRW")));
        assertThat(snapshot.shortTermDebt()).isEqualByComparingTo("1500");
        assertThat(snapshot.priorPeriodShortTermDebt()).isEqualByComparingTo("1000");
        assertThat(snapshot.operatingCashFlow()).isEqualByComparingTo("-250");
        assertThat(snapshot.statementBasis()).isEqualTo("CFS");
        assertThat(snapshot.receiptNumber()).isEqualTo("20260301000001");
    }
    @Test void missingPriorDebtIsNotSubstitutedWithAnotherAccountOrZero() {
        var snapshot = FinancialNormalizer.normalize(2025, "OFS", List.of(
                row("유동성장기차입금 및 단기차입금", "custom", "BS", "900", "800", "KRW"),
                row("단기차입금", "dart_ShortTermBorrowings", "BS", "100", "-", "KRW")));
        assertThat(snapshot.shortTermDebt()).isEqualByComparingTo("100");
        assertThat(snapshot.priorPeriodShortTermDebt()).isNull();
    }
    @Test void ambiguousRowsAndMixedCurrenciesAreNotCompared() {
        var one = row("단기차입금", "dart_ShortTermBorrowings", "BS", "100", "80", "KRW");
        var conflicting = row("단기차입금", "dart_ShortTermBorrowings", "BS", "120", "90", "KRW");
        assertThat(FinancialNormalizer.normalize(2025, "CFS", List.of(one, conflicting)).shortTermDebt()).isNull();
        var mixed = FinancialNormalizer.normalize(2025, "CFS", List.of(one,
                row("매출액", "ifrs-full_Revenue", "IS", "500", "400", "USD")));
        assertThat(mixed.currency()).isNull();
        assertThat(mixed.shortTermDebt()).isNull();
    }
    @Test void wrongStatementSectionAndMissingCurrencyAreNotUsed() {
        var snapshot = FinancialNormalizer.normalize(2025, "CFS", List.of(
                row("단기차입금", "dart_ShortTermBorrowings", "CF", "100", "80", "KRW"),
                row("단기차입금", "dart_ShortTermBorrowings", null, "100", "80", "KRW")));
        assertThat(snapshot.shortTermDebt()).isNull();
        assertThat(FinancialNormalizer.normalize(2025, "CFS", List.of(
                row("단기차입금", "dart_ShortTermBorrowings", "BS", "100", "80", null))).shortTermDebt()).isNull();
    }
}

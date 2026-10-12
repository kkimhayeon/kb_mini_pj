package kb_bridge.domain.company.entity;

import java.math.BigDecimal;

public record FinancialSnapshot(
        String period, BigDecimal revenue, BigDecimal operatingProfit,
        BigDecimal shortTermDebt, BigDecimal priorPeriodShortTermDebt, BigDecimal operatingCashFlow,
        String statementBasis, String currency, String receiptNumber
) {
    public FinancialSnapshot(String period, BigDecimal revenue, BigDecimal operatingProfit,
                             BigDecimal shortTermDebt, BigDecimal priorPeriodShortTermDebt, BigDecimal operatingCashFlow) {
        this(period, revenue, operatingProfit, shortTermDebt, priorPeriodShortTermDebt, operatingCashFlow, null, null, null);
    }
}

package kb_bridge.domain.company.entity;

import java.math.BigDecimal;

public record FinancialSnapshot(
        String period,
        BigDecimal revenue,
        BigDecimal operatingProfit,
        BigDecimal shortTermDebt,
        BigDecimal priorPeriodShortTermDebt,
        BigDecimal totalDebt,
        BigDecimal priorPeriodTotalDebt,
        BigDecimal operatingCashFlow
) {
}

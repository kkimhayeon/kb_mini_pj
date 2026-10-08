package kb_bridge.domain.company.entity;

import java.math.BigDecimal;

public record FinancialSignal(
        String type,
        String status,
        BigDecimal currentValue,
        BigDecimal priorValue,
        String message
) {
}

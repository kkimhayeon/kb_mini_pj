package kb_bridge.domain.company.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PlanKnowledge(
        String planId,
        String domain,
        String scope,
        String knowledgeStatus,
        String planStatus,
        LocalDate lastConfirmedAt,
        BigDecimal amount,
        String currency,
        String expectedAt,
        String expectedAtPrecision,
        String source,
        String evidenceLevel,
        String sourceReference,
        LocalDate validUntil,
        LocalDate reviewDueAt,
        String validityStatus,
        LocalDate recordedAt,
        String note
) {
}

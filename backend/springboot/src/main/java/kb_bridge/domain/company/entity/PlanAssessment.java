package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.util.List;

public record PlanAssessment(
        String planId,
        String domain,
        String scope,
        String knowledgeStatus,
        String planStatus,
        LocalDate comparedFrom,
        String status,
        String changeType,
        String priority,
        String summary,
        List<DisclosureEvidence> evidence
) {
}

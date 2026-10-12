package kb_bridge.domain.company.entity;

import java.util.List;

public record PlanComparison(Long planId, String domain, String projectName, String matchStatus,
                             String comparisonStatus, String message, List<FieldChange> changes, DisclosureEvidence evidence) {
    public record FieldChange(String field, String before, String after) { }
}

package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.util.List;

public record Company(
        String companyId,
        String corpCode,
        String companyName,
        LocalDate consultationDate,
        String investmentPlan,
        String fundingPlan,
        String foreignBusinessPlan,
        String rmMemo,
        List<PlanSummary> plans
) {
    public Company(
            String companyId,
            String companyName,
            LocalDate consultationDate,
            String investmentPlan,
            String fundingPlan,
            String foreignBusinessPlan,
            String rmMemo
    ) {
        this(companyId, null, companyName, consultationDate, investmentPlan, fundingPlan, foreignBusinessPlan, rmMemo, List.of());
    }

    public record PlanSummary(Long planId, String domain, String scope) {
    }
}

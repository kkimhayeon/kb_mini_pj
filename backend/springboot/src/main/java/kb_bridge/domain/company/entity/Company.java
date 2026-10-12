package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
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

    public record PlanSummary(Long planId, String domain, String scope, String planStatus,
                              String projectName, BigDecimal amount, String currency, LocalDate expectedAt,
                              String expectedPrecision, OffsetDateTime asOf) {
        public PlanSummary(Long planId, String domain, String scope) {
            this(planId, domain, scope, null);
        }
        public PlanSummary(Long planId, String domain, String scope, String planStatus) {
            this(planId, domain, scope, planStatus, scope, null, null, null, null, null);
        }
    }
}

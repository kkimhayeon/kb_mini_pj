package kb_bridge.domain.company.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.Company.PlanSummary;
import kb_bridge.domain.company.persistence.PlanFactEntity;

/** Applies partial, confirmed updates independently for each linked plan. */
public final class ConfirmedPlanResolver {
    private ConfirmedPlanResolver() {
    }

    public static Company apply(Company company, List<PlanFactEntity> facts) {
        OffsetDateTime now = OffsetDateTime.now();
        List<PlanFactEntity> usable = facts.stream()
                .filter(fact -> "RM_CONFIRMED".equals(fact.getVerificationStatus()))
                .filter(fact -> fact.getPlan() != null)
                .filter(fact -> fact.getEffectiveFrom() == null || !fact.getEffectiveFrom().isAfter(now))
                .sorted(Comparator.comparing(PlanFactEntity::getEffectiveFrom,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PlanFactEntity::getFactId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        List<PlanSummary> plans = company.plans().stream()
                .map(plan -> merge(plan, usable.stream()
                        .filter(fact -> Objects.equals(fact.getPlan().getPlanId(), plan.planId())
                                && plan.domain().equals(fact.getDomain()))
                        .toList()))
                .toList();
        return new Company(company.companyId(), company.corpCode(), company.companyName(),
                company.consultationDate(),
                domainText(plans, "INVESTMENT", company.investmentPlan()),
                domainText(plans, "FUNDING", company.fundingPlan()),
                domainText(plans, "FX", company.foreignBusinessPlan()),
                company.rmMemo(), plans);
    }

    private static PlanSummary merge(PlanSummary plan, List<PlanFactEntity> facts) {
        if (facts.isEmpty()) {
            return plan;
        }
        String scope = null;
        String status = null;
        BigDecimal amount = null;
        String currency = null;
        LocalDate expected = null;
        String precision = null;
        for (PlanFactEntity fact : facts) {
            if (scope == null && fact.getScope() != null && !fact.getScope().isBlank()) {
                scope = fact.getScope();
            }
            if (status == null && fact.getPlanStatus() != null && !"UNKNOWN".equals(fact.getPlanStatus())) {
                status = fact.getPlanStatus();
            }
            if (amount == null && fact.getAmount() != null) {
                amount = fact.getAmount();
                currency = fact.getCurrency();
            }
            if (expected == null && fact.getExpectedAt() != null) {
                expected = fact.getExpectedAt();
                precision = fact.getExpectedPrecision();
            }
        }
        List<String> parts = new ArrayList<>();
        if (status != null) {
            parts.add(statusLabel(status));
        }
        if (scope != null) {
            parts.add("대상: " + scope);
        } else if (plan.scope() != null && !plan.scope().isBlank()) {
            parts.add("기존 기록: " + plan.scope());
        }
        if (amount != null) {
            parts.add("금액: " + amount.stripTrailingZeros().toPlainString() + " " + currency);
        }
        if (expected != null) {
            parts.add("예정: " + expectedText(expected, precision));
        }
        // UNKNOWN means a new partial fact exists but its status has not been confirmed.
        return new PlanSummary(plan.planId(), plan.domain(), String.join(" / ", parts),
                status == null ? "UNKNOWN" : status,
                scope == null ? plan.projectName() : scope, amount, currency, expected, precision,
                facts.getFirst().getEffectiveFrom());
    }

    private static String domainText(List<PlanSummary> plans, String domain, String fallback) {
        List<PlanSummary> matching = plans.stream().filter(plan -> domain.equals(plan.domain())).toList();
        if (matching.stream().noneMatch(plan -> plan.planStatus() != null)) {
            return fallback;
        }
        return matching.stream().map(PlanSummary::scope).filter(Objects::nonNull)
                .collect(Collectors.joining(" ; "));
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "REVIEW" -> "검토 중";
            case "PLANNED" -> "진행 예정";
            case "IN_PROGRESS" -> "진행 중";
            case "COMPLETED" -> "완료";
            case "CANCELED" -> "취소";
            case "NOT_APPLICABLE" -> "계획 없음";
            default -> "상태 미확인";
        };
    }

    private static String expectedText(LocalDate date, String precision) {
        return switch (precision == null ? "DAY" : precision) {
            case "MONTH" -> date.toString().substring(0, 7);
            case "QUARTER" -> date.getYear() + "년 " + ((date.getMonthValue() - 1) / 3 + 1) + "분기";
            case "HALF" -> date.getYear() + (date.getMonthValue() <= 6 ? "년 상반기" : "년 하반기");
            case "YEAR" -> date.getYear() + "년";
            default -> date.toString();
        };
    }
}

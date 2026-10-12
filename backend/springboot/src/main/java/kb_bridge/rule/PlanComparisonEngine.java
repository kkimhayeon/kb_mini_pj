package kb_bridge.rule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kb_bridge.domain.company.entity.*;
import kb_bridge.domain.company.entity.PlanComparison.FieldChange;

public final class PlanComparisonEngine {
    private PlanComparisonEngine() {}
    public static List<PlanComparison> compare(Company company, List<DisclosureEvidence> evidence) {
        if (evidence == null) return List.of();
        List<PlanComparison> result = new ArrayList<>();
        for (var item : evidence) {
            var details = item.details();
            if (details == null) continue;
            String name = normalized(details.projectName());
            var candidates = company.plans().stream().filter(plan -> details.domain().equals(plan.domain())
                    && specific(name) && name.equals(normalized(plan.projectName()))).toList();
            if (candidates.size() != 1) {
                result.add(new PlanComparison(null, details.domain(), details.projectName(),
                        candidates.isEmpty() ? "UNMATCHED" : "AMBIGUOUS", "INSUFFICIENT",
                        candidates.isEmpty() ? "대상명이 일치하는 계획을 찾지 못했습니다. 동일 사업인지 확인해주세요."
                                : "동일한 대상명의 계획이 여러 개여서 자동 연결하지 않았습니다.", List.of(), item));
                continue;
            }
            var plan = candidates.getFirst();
            if (plan.asOf() != null && isBefore(item.date(), plan.asOf().toLocalDate())) {
                result.add(new PlanComparison(plan.planId(), plan.domain(), details.projectName(), "NAME_MATCH",
                        "HISTORICAL", "확인된 계획 정보보다 이전 공시입니다. 신규 변경으로 판단하지 않습니다.", List.of(), item));
                continue;
            }
            List<FieldChange> changes = new ArrayList<>();
            boolean amountComparable = plan.amount() != null && details.amount() != null && plan.currency() != null
                    && plan.currency().equals(details.currency());
            if (amountComparable && plan.amount().compareTo(details.amount()) != 0) {
                changes.add(new FieldChange("AMOUNT", plan.amount().stripTrailingZeros().toPlainString() + " " + plan.currency(),
                        details.amount().stripTrailingZeros().toPlainString() + " " + details.currency()));
            }
            boolean dateComparable = plan.expectedAt() != null && details.expectedAt() != null
                    && Set.of("DAY", "MONTH", "QUARTER", "HALF", "YEAR").contains(String.valueOf(plan.expectedPrecision()));
            if (dateComparable && !withinPeriod(plan.expectedAt(), plan.expectedPrecision(), details.expectedAt())) {
                changes.add(new FieldChange("EXPECTED_AT", periodLabel(plan.expectedAt(), plan.expectedPrecision()),
                        details.expectedAt().toString()));
            }
            boolean inactive = Set.of("CANCELED", "COMPLETED", "NOT_APPLICABLE").contains(String.valueOf(plan.planStatus()));
            boolean lifecycle = item.title() != null && (item.title().contains("취소") || item.title().contains("철회"));
            String status = !changes.isEmpty() ? "DIFFERENCES"
                    : amountComparable && dateComparable && !lifecycle && !inactive ? "NO_DIFFERENCE" : "INSUFFICIENT";
            String message = switch (status) {
                case "DIFFERENCES" -> "대상명이 일치하는 계획과 금액 또는 일정이 다릅니다. 동일 사업·비교 기준인지 RM 확인이 필요합니다.";
                case "NO_DIFFERENCE" -> "대상명이 일치하며 비교 가능한 금액·일정 차이가 없습니다.";
                default -> "대상명은 일치하지만 통화·금액·일정 또는 공시 상태를 추가 확인해야 합니다.";
            };
            result.add(new PlanComparison(plan.planId(), plan.domain(), details.projectName(), "NAME_MATCH",
                    status, message, List.copyOf(changes), item));
        }
        return List.copyOf(result);
    }
    private static boolean specific(String value) {
        return value.length() >= 3 && !Set.of("공장", "부동산", "토지", "건물", "토지및건물", "설비", "주식", "유형자산").contains(value);
    }
    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
    private static boolean isBefore(String value, LocalDate baseline) {
        try { return value != null && LocalDate.parse(value).isBefore(baseline); }
        catch (java.time.DateTimeException exception) { return false; }
    }
    private static boolean withinPeriod(LocalDate baseline, String precision, LocalDate value) {
        return switch (precision) {
            case "DAY" -> baseline.equals(value);
            case "MONTH" -> baseline.getYear() == value.getYear() && baseline.getMonth() == value.getMonth();
            case "QUARTER" -> baseline.getYear() == value.getYear() && (baseline.getMonthValue()-1)/3 == (value.getMonthValue()-1)/3;
            case "HALF" -> baseline.getYear() == value.getYear() && (baseline.getMonthValue()-1)/6 == (value.getMonthValue()-1)/6;
            case "YEAR" -> baseline.getYear() == value.getYear();
            default -> false;
        };
    }
    private static String periodLabel(LocalDate date, String precision) {
        return switch (precision) {
            case "MONTH" -> date.toString().substring(0,7);
            case "QUARTER" -> date.getYear() + "년 " + ((date.getMonthValue()-1)/3+1) + "분기";
            case "HALF" -> date.getYear() + (date.getMonthValue()<=6 ? "년 상반기" : "년 하반기");
            case "YEAR" -> date.getYear() + "년";
            default -> date.toString();
        };
    }
}

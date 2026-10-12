package kb_bridge.rule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.PlanComparison.FieldChange;

@Component
public class GapRuleEngine {

    private static final long STALE_DAYS = 180;

    public enum GapType {
        INVESTMENT_PLAN_GAP,
        FUNDING_PLAN_GAP,
        FX_BUSINESS_GAP
    }

    public enum ChangeType {
        CONFLICT,
        NEEDS_CONFIRMATION,
        SPECIFIED,
        NEW_INFO
    }

    public record Finding(GapType type, ChangeType changeType, String existingInfo, String latestInfo,
                          List<DisclosureEvidence> evidence, Long planId, List<FieldChange> changes) {
        public Finding(GapType type, ChangeType changeType, String existingInfo, String latestInfo,
                       List<DisclosureEvidence> evidence) {
            this(type, changeType, existingInfo, latestInfo, evidence, null, List.of());
        }
    }

    private enum PlanSignal {
        EXPLICIT_NONE,
        REVIEW,
        PLANNED,
        INACTIVE,
        UNKNOWN
    }

    private record DomainRule(
            String domain,
            GapType gapType,
            String rmPlan,
            PlanSignal signal,
            String noPlanLatestInfo,
            String changedLatestInfo,
            String reviewLatestInfo,
            List<String> includeKeywords,
            List<String> excludeKeywords
    ) {
    }

    private record DisclosureMatch(DisclosureEvidence evidence, int score) {
    }

    public List<Finding> detect(
            Company company,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures
    ) {
        List<Finding> findings = new ArrayList<>();
        List<DisclosureEvidence> safeDisclosures = disclosures == null ? List.of() : disclosures;
        var comparisons = PlanComparisonEngine.compare(company, safeDisclosures);
        var matched = comparisons.stream().filter(item -> "NAME_MATCH".equals(item.matchStatus())).toList();
        for (var comparison : matched) {
            if ("NO_DIFFERENCE".equals(comparison.comparisonStatus()) || "HISTORICAL".equals(comparison.comparisonStatus())) continue;
            GapType type = switch (comparison.domain()) {
                case "FUNDING" -> GapType.FUNDING_PLAN_GAP;
                case "FX" -> GapType.FX_BUSINESS_GAP;
                default -> GapType.INVESTMENT_PLAN_GAP;
            };
            String existing = company.plans().stream().filter(plan -> plan.planId().equals(comparison.planId()))
                    .findFirst().orElseThrow().scope();
            String difference = comparison.changes().stream().map(change -> change.field() + ": " + change.before() + " → " + change.after())
                    .collect(java.util.stream.Collectors.joining("; "));
            findings.add(new Finding(type, ChangeType.NEEDS_CONFIRMATION, existing,
                    comparison.message() + (difference.isEmpty() ? "" : " " + difference),
                    List.of(comparison.evidence()), comparison.planId(), comparison.changes()));
        }
        safeDisclosures = safeDisclosures.stream().filter(item -> matched.stream()
                .noneMatch(comparison -> comparison.evidence().equals(item))).toList();

        for (DomainRule rule : rules(company)) {
            List<DisclosureMatch> matches = matchDisclosures(safeDisclosures, rule);
            List<DisclosureEvidence> evidence = matches.stream()
                    .map(DisclosureMatch::evidence)
                    .toList();
            PlanSignal signal = rule.signal();

            if (!evidence.isEmpty()) {
                findings.add(toFinding(rule, signal, evidence));
            } else if (rule.gapType() == GapType.FUNDING_PLAN_GAP
                    && (signal == PlanSignal.EXPLICIT_NONE || signal == PlanSignal.UNKNOWN)
                    && hasMaterialDebtIncrease(financials)) {
                findings.add(new Finding(
                        GapType.FUNDING_PLAN_GAP,
                        signal == PlanSignal.EXPLICIT_NONE ? ChangeType.CONFLICT : ChangeType.NEEDS_CONFIRMATION,
                        existingInfo(rule.rmPlan(), signal),
                        signal == PlanSignal.EXPLICIT_NONE
                                ? "RM 정보에는 자금조달 계획이 없지만 OpenDART 재무정보에서 단기차입금이 전년 대비 20% 이상 증가했습니다."
                                : "RM 자금조달 정보가 미확인인 상태에서 단기차입금이 전년 대비 20% 이상 증가하여 확인이 필요합니다.",
                        List.of(financialEvidence(financials))
                ));
            }
        }

        if (findings.isEmpty() && isStale(company.consultationDate())) {
            return List.of();
        }
        return List.copyOf(findings);
    }

    public boolean needsFinancialStatements(Company company) {
        PlanSignal signal = classifyPlan(company, "FUNDING", company.fundingPlan());
        return signal == PlanSignal.EXPLICIT_NONE || signal == PlanSignal.UNKNOWN;
    }

    public boolean needsDisclosures(Company company) {
        return true;
    }

    private List<DomainRule> rules(Company company) {
        return List.of(
                new DomainRule(
                        "INVESTMENT",
                        GapType.INVESTMENT_PLAN_GAP,
                        company.investmentPlan(),
                        classifyPlan(company, "INVESTMENT", company.investmentPlan()),
                        "RM 정보에는 투자 계획이 없지만 OpenDART에서 투자 또는 시설 관련 공시가 발견되었습니다.",
                        "기존 RM 투자 정보와 다른 범위의 투자 또는 시설 관련 공시가 발견되었습니다.",
                        "기존 RM 정보는 투자 검토 단계였고, OpenDART에서 관련 공시가 발견되어 진행 상태 확인이 필요합니다.",
                        List.of("시설투자", "신규시설", "유형자산취득", "유형자산양수", "타법인주식및출자증권양수", "공장신설", "공장", "설비투자", "시설증설", "투자결정", "타법인주식취득"),
                        List.of("취소", "철회", "해지", "청산", "처분")
                ),
                new DomainRule(
                        "FUNDING",
                        GapType.FUNDING_PLAN_GAP,
                        company.fundingPlan(),
                        classifyPlan(company, "FUNDING", company.fundingPlan()),
                        "RM 정보에는 자금조달 계획이 없지만 OpenDART에서 차입 또는 증자 관련 공시가 발견되었습니다.",
                        "기존 RM 자금조달 정보와 다른 자금조달 관련 공시가 발견되었습니다.",
                        "기존 RM 정보는 자금조달 검토 단계였고, OpenDART에서 관련 공시가 발견되어 진행 상태 확인이 필요합니다.",
                        List.of("회사채", "사채발행", "차입", "유상증자", "전환사채", "신주인수권부사채", "자금조달", "단기차입금", "금전대여"),
                        List.of("취소", "철회", "해지", "상환완료")
                ),
                new DomainRule(
                        "FX",
                        GapType.FX_BUSINESS_GAP,
                        company.foreignBusinessPlan(),
                        classifyPlan(company, "FX", company.foreignBusinessPlan()),
                        "RM 정보에는 해외사업 또는 외환 관련 계획이 없지만 OpenDART에서 해외사업, 출자, 법인 관련 공시가 발견되었습니다.",
                        "기존 RM 해외사업 정보와 다른 해외사업 또는 출자 관련 공시가 발견되었습니다.",
                        "기존 RM 정보는 해외사업 검토 단계였고, OpenDART에서 관련 공시가 발견되어 진행 상태 확인이 필요합니다.",
                        List.of("해외법인", "해외사업", "해외진출", "해외투자", "해외공장", "국외사업", "외화", "환위험", "출자", "타법인주식취득", "타법인주식및출자증권양수"),
                        List.of("취소", "철회", "해지", "청산", "처분")
                )
        );
    }

    private Finding toFinding(DomainRule rule, PlanSignal signal, List<DisclosureEvidence> evidence) {
        if (evidence.stream().anyMatch(item -> containsAny(normalize(item.title()), rule.excludeKeywords()))) {
            return new Finding(rule.gapType(), ChangeType.NEEDS_CONFIRMATION,
                    existingInfo(rule.rmPlan(), signal),
                    "기존 RM 계획과 관련된 취소·철회·종료 공시가 발견되어 동일 계획의 변경인지 확인이 필요합니다.",
                    evidence);
        }
        String latestInfo = switch (signal) {
            case EXPLICIT_NONE -> rule.noPlanLatestInfo();
            case REVIEW -> rule.reviewLatestInfo();
            case PLANNED -> sameScopeAlreadyKnown(rule.rmPlan(), evidence)
                    ? "기존 RM 계획과 유사한 공개정보가 발견되었지만, 금액·시기·진행상태 재확인이 필요합니다."
                    : rule.changedLatestInfo();
            case INACTIVE -> "기존 RM 계획은 완료 또는 취소 상태이며, 관련 공시가 동일 건인지 추가 확인이 필요합니다.";
            case UNKNOWN -> "RM 정보가 불명확한 상태에서 OpenDART 관련 공시가 발견되어 확인이 필요합니다.";
        };
        ChangeType changeType = switch (signal) {
            case EXPLICIT_NONE -> ChangeType.CONFLICT;
            case REVIEW, INACTIVE, UNKNOWN -> ChangeType.NEEDS_CONFIRMATION;
            case PLANNED -> sameScopeAlreadyKnown(rule.rmPlan(), evidence)
                    ? ChangeType.NEEDS_CONFIRMATION : ChangeType.SPECIFIED;
        };
        return new Finding(rule.gapType(), changeType, existingInfo(rule.rmPlan(), signal), latestInfo, evidence);
    }

    private List<DisclosureMatch> matchDisclosures(List<DisclosureEvidence> disclosures, DomainRule rule) {
        return disclosures.stream()
                .map(disclosure -> new DisclosureMatch(disclosure, score(disclosure, rule)))
                .filter(match -> match.score() > 0)
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .toList();
    }

    private int score(DisclosureEvidence disclosure, DomainRule rule) {
        String title = normalize(disclosure.title());
        if (title.isBlank() || !containsAny(title, rule.includeKeywords())) {
            return 0;
        }
        // A domestic acquisition alone is not evidence of overseas business.
        if (disclosure.details() != null && !rule.domain().equals(disclosure.details().domain())) return 0;
        if (rule.gapType() == GapType.FX_BUSINESS_GAP
                && !(disclosure.details() != null && "FX".equals(disclosure.details().domain()))
                && !containsAny(title, List.of("해외", "국외", "외화", "환위험"))) {
            return 0;
        }
        if (containsAny(title, rule.excludeKeywords()) && !relatesToExistingPlan(rule, title)) {
            return 0;
        }
        int score = 0;
        for (String keyword : rule.includeKeywords()) {
            if (title.contains(normalize(keyword))) {
                score += keyword.length() >= 4 ? 2 : 1;
            }
        }
        if (title.contains("결정") || title.contains("발행") || title.contains("취득") || title.contains("증가")) {
            score += 1;
        }
        return score;
    }

    private boolean relatesToExistingPlan(DomainRule rule, String title) {
        PlanSignal signal = rule.signal();
        if (signal != PlanSignal.PLANNED && signal != PlanSignal.REVIEW) {
            return false;
        }
        String plan = normalize(rule.rmPlan());
        return rule.includeKeywords().stream()
                .map(this::normalize)
                .anyMatch(keyword -> title.contains(keyword) && plan.contains(keyword));
    }

    private boolean sameScopeAlreadyKnown(String rmPlan, List<DisclosureEvidence> evidence) {
        if (rmPlan == null || rmPlan.isBlank()) {
            return false;
        }
        List<String> planTokens = meaningfulTokens(rmPlan);
        if (planTokens.isEmpty()) {
            return false;
        }
        return evidence.stream()
                .map(DisclosureEvidence::title)
                .map(this::normalize)
                .anyMatch(title -> planTokens.stream().anyMatch(title::contains));
    }

    private List<String> meaningfulTokens(String value) {
        String normalized = normalize(value)
                .replace("가상샘플", "")
                .replace("계획", "")
                .replace("예정", "")
                .replace("검토", "");
        return Arrays.stream(normalized.split("[·,/()\\-]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !token.equals("해당분야") && !token.equals("없음"))
                .toList();
    }

    private PlanSignal classifyPlan(Company company, String domain, String fallback) {
        List<Company.PlanSummary> plans = company.plans().stream()
                .filter(plan -> domain.equals(plan.domain()))
                .toList();
        if (plans.stream().noneMatch(plan -> plan.planStatus() != null)) {
            return classifyPlan(fallback);
        }
        if (plans.size() != 1) {
            return PlanSignal.UNKNOWN;
        }
        return switch (plans.getFirst().planStatus()) {
            case "NOT_APPLICABLE" -> PlanSignal.EXPLICIT_NONE;
            case "REVIEW" -> PlanSignal.REVIEW;
            case "PLANNED", "IN_PROGRESS" -> PlanSignal.PLANNED;
            case "COMPLETED", "CANCELED" -> PlanSignal.INACTIVE;
            default -> PlanSignal.UNKNOWN;
        };
    }

    private PlanSignal classifyPlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return PlanSignal.UNKNOWN;
        }
        String normalized = normalize(plan);
        if (normalized.contains("해당분야계획없음")
                || normalized.contains("계획없음")
                || normalized.equals("없음")
                || normalized.contains("없습니다")
                || normalized.contains("없다")
                || normalized.contains("미계획")) {
            return PlanSignal.EXPLICIT_NONE;
        }
        if (normalized.contains("검토") || normalized.contains("논의") || normalized.contains("가능성") || normalized.contains("미정")) {
            return PlanSignal.REVIEW;
        }
        if (normalized.contains("예정") || normalized.contains("계획") || normalized.contains("확정") || normalized.contains("진행")) {
            return PlanSignal.PLANNED;
        }
        return PlanSignal.UNKNOWN;
    }

    private String existingInfo(String plan, PlanSignal signal) {
        if (signal == PlanSignal.EXPLICIT_NONE) {
            return "계획 없음";
        }
        if (plan == null || plan.isBlank()) {
            return "RM 정보 없음";
        }
        return plan;
    }

    private boolean containsAny(String normalizedTitle, List<String> keywords) {
        return keywords.stream().map(this::normalize).anyMatch(normalizedTitle::contains);
    }

    private boolean isStale(LocalDate consultationDate) {
        return consultationDate != null && ChronoUnit.DAYS.between(consultationDate, LocalDate.now()) > STALE_DAYS;
    }

    private DisclosureEvidence financialEvidence(FinancialSnapshot financials) {
        String period = financials == null ? null : financials.period();
        return new DisclosureEvidence(
                "OpenDART 재무정보",
                "단기차입금 전년 대비 20% 이상 증가",
                period,
                null
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean hasMaterialDebtIncrease(FinancialSnapshot financials) {
        if (financials == null
                || financials.shortTermDebt() == null
                || financials.priorPeriodShortTermDebt() == null
                || financials.priorPeriodShortTermDebt().signum() <= 0) {
            return false;
        }
        BigDecimal threshold = financials.priorPeriodShortTermDebt().multiply(new BigDecimal("1.20"));
        return financials.shortTermDebt().compareTo(threshold) >= 0;
    }
}

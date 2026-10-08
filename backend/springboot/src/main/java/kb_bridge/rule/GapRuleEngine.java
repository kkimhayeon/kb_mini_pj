package kb_bridge.rule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSignal;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.PlanAssessment;
import kb_bridge.domain.company.entity.PlanKnowledge;

@Component
public class GapRuleEngine {

    public enum GapType {
        INVESTMENT_PLAN_GAP,
        FUNDING_PLAN_GAP,
        FX_BUSINESS_GAP
    }

    public record Finding(
            GapType type,
            String existingInfo,
            String latestInfo,
            List<DisclosureEvidence> evidence,
            String domain,
            String planId,
            String changeType,
            String priority
    ) {
    }

    public record Evaluation(
            List<PlanAssessment> assessments,
            List<Finding> findings,
            List<FinancialSignal> financialSignals
    ) {
    }

    public Evaluation evaluate(
            Company company,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures,
            LocalDate analyzedAt,
            String disclosureStatus
    ) {
        List<PlanAssessment> assessments = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        for (PlanKnowledge plan : company.plans()) {
            LocalDate planSearchStart = comparisonStart(plan, company, analyzedAt);
            List<DisclosureEvidence> candidates = disclosures.stream()
                    .filter(disclosure -> dateIsInRange(disclosure.date(), planSearchStart, analyzedAt))
                    .filter(disclosure -> matchesDomain(plan.domain(), disclosure))
                    .filter(disclosure -> matchesScope(plan.scope(), disclosure))
                    .toList();
            PlanAssessment assessment = assessPlan(
                    plan, candidates, planSearchStart, analyzedAt, disclosureStatus);
            assessments.add(assessment);
            if (isConfirmedChange(assessment.changeType())) {
                findings.add(new Finding(
                        gapType(plan.domain()),
                        existingInfo(plan),
                        assessment.summary(),
                        assessment.evidence(),
                        plan.domain(),
                        plan.planId(),
                        assessment.changeType(),
                        assessment.priority()
                ));
            }
        }

        assessments.sort((left, right) -> Integer.compare(
                priorityRank(left.priority()), priorityRank(right.priority())));
        findings.sort((left, right) -> Integer.compare(
                priorityRank(left.priority()), priorityRank(right.priority())));
        return new Evaluation(
                List.copyOf(assessments),
                List.copyOf(findings),
                financialSignals(financials)
        );
    }

    public boolean needsFinancialStatements(Company company) {
        return company.plans().stream().anyMatch(plan -> "FUNDING".equals(plan.domain()));
    }

    public boolean needsDisclosures(Company company) {
        return !company.plans().isEmpty();
    }

    private PlanAssessment assessPlan(
            PlanKnowledge plan,
            List<DisclosureEvidence> candidates,
            LocalDate comparedFrom,
            LocalDate analyzedAt,
            String disclosureStatus
    ) {
        if (isReviewOverdue(plan, analyzedAt)) {
            return result(plan, comparedFrom, "RECONFIRM_REQUIRED", "RECONFIRMATION", "PRIORITY_3",
                    "RM 정보의 유효기간 또는 재확인 예정일이 지났습니다. 계획 변경으로 단정하지 않습니다.",
                    List.of());
        }

        if ("FAILED".equals(disclosureStatus)) {
            return result(plan, comparedFrom, "RETRIEVAL_FAILED", "NONE", "NONE",
                    "공시 조회에 실패해 이 계획의 변경 여부를 평가할 수 없습니다.", List.of());
        }

        if (candidates.stream().anyMatch(evidence -> "UNRESOLVED".equals(evidence.correctionStatus()))) {
            return result(plan, comparedFrom, "JUDGMENT_PENDING", "ADDITIONAL_CONFIRMATION_REQUIRED", "PRIORITY_2",
                    "관련 정정공시와 원공시의 연결을 확인할 수 없어 판단을 보류합니다.", candidates);
        }

        List<DisclosureEvidence> directEvidence = candidates.stream()
                .filter(evidence -> !isCorrectionSuperseded(evidence, candidates))
                .filter(evidence -> hasRelevantDocumentText(plan, evidence))
                .toList();
        if (directEvidence.isEmpty()) {
            if ("PARTIAL_FAILURE".equals(disclosureStatus)) {
                return result(plan, comparedFrom, "JUDGMENT_PENDING",
                        "ADDITIONAL_CONFIRMATION_REQUIRED", "PRIORITY_2",
                        "공시 원문 일부를 조회하지 못해 변경 근거 유무를 판단할 수 없습니다.", candidates);
            }
            if (!candidates.isEmpty()) {
                return result(plan, comparedFrom, "JUDGMENT_PENDING", "ADDITIONAL_CONFIRMATION_REQUIRED", "PRIORITY_2",
                        "관련 공시 제목은 확인했으나 원문에서 동일 계획의 직접 근거를 확인하지 못했습니다.",
                        candidates);
            }
            return result(plan, comparedFrom, "CHANGE_EVIDENCE_NOT_FOUND", "NONE", "NONE",
                    "조회 범위에서 관련 변경 근거를 찾지 못했습니다. 기존 정보가 현재도 유효함을 의미하지 않습니다.",
                    List.of());
        }

        boolean hasSameDayEvidence = directEvidence.stream()
                .anyMatch(evidence -> comparedFrom.toString().equals(evidence.date()));
        if (hasSameDayEvidence) {
            return result(plan, comparedFrom, "JUDGMENT_PENDING", "ADDITIONAL_CONFIRMATION_REQUIRED", "PRIORITY_2",
                    "확인일과 공시일이 같은 날짜여서 선후관계를 판단할 수 없습니다.", directEvidence);
        }

        DisclosureEvidence latest = directEvidence.get(0);
        String text = normalize(latest.title() + " " + latest.content());
        if (containsAny(text, "취소", "철회", "해지", "청산")) {
            return result(plan, comparedFrom, "ACTION_REQUIRED", "PLAN_TERMINATED", "PRIORITY_1",
                    "동일 계획의 취소·철회·종료를 시사하는 원문 근거가 확인되었습니다.", directEvidence);
        }
        if ("EXPLICIT_NO".equals(plan.knowledgeStatus())) {
            return result(plan, comparedFrom, "ACTION_REQUIRED", "CONTRADICTION", "PRIORITY_1",
                    "기존에 계획이 없다고 기록된 항목과 이후 공시 원문의 직접 근거가 상충합니다. 적용 범위와 시점을 RM이 확인해야 합니다.",
                    directEvidence);
        }
        if ("UNKNOWN".equals(plan.knowledgeStatus()) || "UNRECORDED".equals(plan.knowledgeStatus())) {
            return result(plan, comparedFrom, "ACTION_REQUIRED", "NEW_INFORMATION", "PRIORITY_2",
                    "기존에 확인되지 않았거나 기록되지 않은 항목에서 계획의 직접 근거가 확인되었습니다.",
                    directEvidence);
        }
        if (plan.amount() == null || plan.expectedAt() == null) {
            return result(plan, comparedFrom, "ACTION_REQUIRED", "PLAN_DETAIL_UPDATE", "PRIORITY_3",
                    "기존 계획과 관련된 직접 근거가 확인되었습니다. 기존에 미정이던 규모·시기 등 세부정보를 검토하세요.",
                    directEvidence);
        }
        return result(plan, comparedFrom, "JUDGMENT_PENDING", "ADDITIONAL_CONFIRMATION_REQUIRED", "PRIORITY_2",
                "동일 계획의 직접 근거는 확인했으나 기존 확정 정보와 비교할 세부 값이 부족해 변경 여부를 판단할 수 없습니다.",
                directEvidence);
    }

    private boolean hasRelevantDocumentText(PlanKnowledge plan, DisclosureEvidence evidence) {
        if (evidence.content() == null || evidence.content().isBlank()) {
            return false;
        }
        String text = normalize(evidence.content());
        if (!containsDomainKeyword(plan.domain(), text)) {
            return false;
        }
        return scopeMatchesText(plan.scope(), text);
    }

    private boolean matchesDomain(String domain, DisclosureEvidence evidence) {
        String title = normalize(evidence.title());
        return containsDomainKeyword(domain, title);
    }

    private boolean matchesScope(String scope, DisclosureEvidence evidence) {
        return scopeMatchesText(scope, normalize(evidence.title() + " " + evidence.content()));
    }

    private boolean containsDomainKeyword(String domain, String text) {
        return switch (domain) {
            case "INVESTMENT" -> containsAny(text, "시설투자", "신규시설", "유형자산취득", "공장신설",
                    "설비투자", "시설증설", "투자결정");
            case "FUNDING" -> containsAny(text, "회사채", "사채발행", "차입", "유상증자",
                    "전환사채", "신주인수권부사채", "자금조달");
            case "FOREIGN_BUSINESS" -> containsAny(text, "해외법인", "해외사업", "해외진출",
                    "해외투자", "해외공장", "국외사업", "외국법인");
            default -> false;
        };
    }

    private boolean scopeMatchesText(String scope, String text) {
        if (scope == null || scope.isBlank()) {
            return true;
        }
        String normalizedScope = scope.toLowerCase(Locale.ROOT)
                .replaceAll("[^가-힣a-z0-9 ]", " ")
                .trim();
        if (normalizedScope.length() < 2) {
            return true;
        }
        if (text.contains(normalizedScope.replaceAll("\\s+", ""))) {
            return true;
        }
        String[] terms = normalizedScope.split("[^가-힣a-z0-9]+");
        List<String> specificTerms = java.util.Arrays.stream(terms)
                .filter(term -> term.length() >= 2)
                .filter(term -> !containsAny(term, "시설", "투자", "계획", "예정", "검토", "사업",
                        "차입", "발행", "증설", "확대", "추진"))
                .toList();
        return specificTerms.isEmpty() || specificTerms.stream().anyMatch(text::contains);
    }

    private boolean isCorrectionSuperseded(
            DisclosureEvidence evidence,
            List<DisclosureEvidence> candidates
    ) {
        if (!"NOT_CORRECTION".equals(evidence.correctionStatus())) {
            return false;
        }
        return candidates.stream()
                .filter(candidate -> "LINKED".equals(candidate.correctionStatus()))
                .anyMatch(candidate -> normalizeReportTitle(candidate.title())
                        .equals(normalizeReportTitle(evidence.title())));
    }

    private String normalizeReportTitle(String title) {
        return normalize(title)
                .replaceAll("\\[.*?정정.*?\\]", "")
                .replaceAll("\\(.*?정정.*?\\)", "")
                .replace("정정", "");
    }

    private boolean dateIsInRange(String date, LocalDate from, LocalDate to) {
        if (date == null || date.isBlank()) {
            return true;
        }
        LocalDate disclosureDate = LocalDate.parse(date);
        return !disclosureDate.isBefore(from) && !disclosureDate.isAfter(to);
    }

    private boolean isReviewOverdue(PlanKnowledge plan, LocalDate analyzedAt) {
        return (plan.reviewDueAt() != null && plan.reviewDueAt().isBefore(analyzedAt))
                || (plan.validUntil() != null && plan.validUntil().isBefore(analyzedAt));
    }

    private PlanAssessment result(
            PlanKnowledge plan,
            LocalDate comparedFrom,
            String status,
            String changeType,
            String priority,
            String summary,
            List<DisclosureEvidence> evidence
    ) {
        return new PlanAssessment(
                plan.planId(),
                plan.domain(),
                plan.scope(),
                plan.knowledgeStatus(),
                plan.planStatus(),
                comparedFrom,
                status,
                changeType,
                priority,
                summary,
                List.copyOf(evidence)
        );
    }

    private LocalDate comparisonStart(PlanKnowledge plan, Company company, LocalDate analyzedAt) {
        if (plan.lastConfirmedAt() != null) {
            return plan.lastConfirmedAt();
        }
        if (company.consultationDate() != null) {
            return company.consultationDate();
        }
        return analyzedAt.minusMonths(12);
    }

    private String existingInfo(PlanKnowledge plan) {
        if ("EXPLICIT_NO".equals(plan.knowledgeStatus())) {
            return "명시적 없음 (계획 상태: " + plan.planStatus() + ")";
        }
        if ("UNKNOWN".equals(plan.knowledgeStatus())) {
            return "미확인";
        }
        if ("UNRECORDED".equals(plan.knowledgeStatus())) {
            return "미기록";
        }
        return plan.scope() == null ? "확인할 수 없음" : plan.scope();
    }

    private GapType gapType(String domain) {
        return switch (domain) {
            case "INVESTMENT" -> GapType.INVESTMENT_PLAN_GAP;
            case "FUNDING" -> GapType.FUNDING_PLAN_GAP;
            case "FOREIGN_BUSINESS" -> GapType.FX_BUSINESS_GAP;
            default -> throw new IllegalArgumentException("Unsupported plan domain: " + domain);
        };
    }

    private boolean isConfirmedChange(String changeType) {
        return List.of("CONTRADICTION", "NEW_INFORMATION", "PLAN_DETAIL_UPDATE", "PLAN_TERMINATED")
                .contains(changeType);
    }

    private int priorityRank(String priority) {
        return switch (priority) {
            case "PRIORITY_1" -> 1;
            case "PRIORITY_2" -> 2;
            case "PRIORITY_3" -> 3;
            default -> 4;
        };
    }

    private List<FinancialSignal> financialSignals(FinancialSnapshot financials) {
        if (financials == null
                || financials.totalDebt() == null
                || financials.priorPeriodTotalDebt() == null
                || financials.priorPeriodTotalDebt().signum() <= 0) {
            return List.of();
        }
        BigDecimal threshold = financials.priorPeriodTotalDebt().multiply(new BigDecimal("1.20"));
        if (financials.totalDebt().compareTo(threshold) < 0) {
            return List.of();
        }
        return List.of(new FinancialSignal(
                "TOTAL_DEBT_INCREASE",
                "INVESTIGATION_CANDIDATE",
                financials.totalDebt(),
                financials.priorPeriodTotalDebt(),
                "비교 가능한 총차입금이 직전 기간 대비 20% 이상 증가했습니다. 조사 후보이며 공시 직접 근거가 확인되기 전까지 Gap으로 확정하지 않습니다."
        ));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

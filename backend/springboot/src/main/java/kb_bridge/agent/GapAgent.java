package kb_bridge.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.web.client.RestClientException;

import org.springframework.stereotype.Component;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.GapResult;
import kb_bridge.rule.GapRuleEngine;
import kb_bridge.rule.GapRuleEngine.Finding;
import kb_bridge.rule.GapRuleEngine.GapType;

@Component
public class GapAgent {

    public record ResearchPlan(boolean financialStatements, boolean disclosures) {
    }

    private final GapRuleEngine gapRuleEngine;
    private final GeminiInsightService geminiInsightService;

    public GapAgent(GapRuleEngine gapRuleEngine, GeminiInsightService geminiInsightService) {
        this.gapRuleEngine = gapRuleEngine;
        this.geminiInsightService = geminiInsightService;
    }

    public ResearchPlan planResearch(Company company) {
        return new ResearchPlan(
                gapRuleEngine.needsFinancialStatements(company),
                gapRuleEngine.needsDisclosures(company)
        );
    }

    public GapAnalysisResponse analyze(
            Company company,
            String corpCode,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures
    ) {
        List<GapResult> gaps = gapRuleEngine.detect(company, financials, disclosures).stream()
                .map(this::toGapResult)
                .toList();
        String message = gaps.isEmpty()
                ? "현재 기존 정보와 확인 가능한 공개정보 사이에서 변경 후보를 찾지 못했습니다."
                : gaps.size() + "개의 고객정보 업데이트 후보를 확인했습니다.";
        return new GapAnalysisResponse(company, corpCode, financials, gaps, message)
                .withComparisons(kb_bridge.rule.PlanComparisonEngine.compare(company, disclosures))
                .withDisclosures(disclosures == null ? List.of() : disclosures);
    }

    private GapResult toGapResult(Finding finding) {
        Optional<GeminiInsightService.Insight> insight;
        boolean geminiFailed = false;
        try {
            insight = geminiInsightService.generate(finding);
        } catch (RestClientException | IllegalStateException exception) {
            // Keep verified facts and rule results when optional explanation generation fails.
            insight = Optional.empty();
            geminiFailed = true;
        }
        return new GapResult(
                finding.type(),
                changeType(finding),
                assessmentStatus(finding),
                displayLabel(finding),
                severity(finding),
                finding.existingInfo(),
                finding.latestInfo(),
                insight.map(GeminiInsightService.Insight::reason)
                        .orElse(reasonFor(finding)),
                insight.isPresent() ? "Gemini" : geminiFailed ? "Rule fallback (Gemini failed)" : "Rule fallback",
                finding.evidence(),
                insight.map(GeminiInsightService.Insight::questions)
                        .orElseGet(() -> questionsFor(finding)),
                finding.planId(),
                finding.changes()
        );
    }


    private String changeType(Finding finding) {
        return finding.changeType().name();
    }

    private String assessmentStatus(Finding finding) {
        return "NEEDS_CONFIRMATION".equals(changeType(finding)) ? "PENDING" : "CONFIRMED";
    }

    private String displayLabel(Finding finding) {
        return switch (changeType(finding)) {
            case "CONFLICT" -> "기존 정보와 상충";
            case "NEEDS_CONFIRMATION" -> "추가 확인 필요";
            case "SPECIFIED" -> "기존 계획과 다른 공개정보";
            case "NEW_INFO" -> "신규 공개정보 발견";
            default -> "확인 필요";
        };
    }

    private String severity(Finding finding) {
        return switch (changeType(finding)) {
            case "CONFLICT" -> "HIGH";
            case "SPECIFIED" -> "MEDIUM";
            case "NEEDS_CONFIRMATION" -> "MEDIUM";
            default -> "LOW";
        };
    }
    private String reasonFor(Finding finding) {
        String evidenceTitle = firstEvidenceTitle(finding.evidence());
        return "기존 정보와 최근 공개정보 사이에 확인이 필요한 차이가 있습니다. 근거: " + evidenceTitle;
    }

    private List<String> questionsFor(Finding finding) {
        String existingInfo = finding.existingInfo() == null || finding.existingInfo().isBlank()
                ? "기존 정보 없음"
                : finding.existingInfo();
        String evidenceTitle = firstEvidenceTitle(finding.evidence());

        return switch (finding.type()) {
            case INVESTMENT_PLAN_GAP -> List.of(
                    "기존 정보에 의하면 '" + existingInfo + "'로 파악되어 있었는데, 최근 '" + evidenceTitle + "' 공시와 관련해 투자 범위나 일정이 변경된 부분이 있습니까?",
                    "해당 투자 건의 필요 자금 규모와 조달 방식은 어떻게 계획하고 있습니까?"
            );
            case FUNDING_PLAN_GAP -> List.of(
                    "기존 정보에 의하면 '" + existingInfo + "'로 파악되어 있었는데, 최근 '" + evidenceTitle + "' 근거와 관련해 자금조달 계획이 변경되었습니까?",
                    "이번 자금조달의 사용 목적, 규모, 상환 또는 후속 조달 계획은 어떻게 보고 있습니까?"
            );
            case FX_BUSINESS_GAP -> List.of(
                    "기존 정보에 의하면 '" + existingInfo + "'로 파악되어 있었는데, 최근 '" + evidenceTitle + "' 공시와 관련해 해외사업 또는 출자 계획이 변경되었습니까?",
                    "해외사업 진행 단계, 필요 외화 규모, 환위험 관리 계획은 어떻게 보고 있습니까?"
            );
        };
    }

    private String firstEvidenceTitle(List<DisclosureEvidence> evidence) {
        if (evidence == null || evidence.isEmpty() || evidence.get(0).title() == null || evidence.get(0).title().isBlank()) {
            return "확인된 공개정보";
        }
        return evidence.get(0).title().trim();
    }
}

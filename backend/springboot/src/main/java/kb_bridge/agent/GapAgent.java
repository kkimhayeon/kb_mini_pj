package kb_bridge.agent;

import java.time.LocalDate;
import java.util.List;

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
    private final OpenAiInsightService openAiInsightService;

    public GapAgent(GapRuleEngine gapRuleEngine, OpenAiInsightService openAiInsightService) {
        this.gapRuleEngine = gapRuleEngine;
        this.openAiInsightService = openAiInsightService;
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
            List<DisclosureEvidence> disclosures,
            LocalDate searchedFrom,
            LocalDate analyzedAt,
            String collectionStatus,
            String companyStatus,
            String disclosureStatus,
            String financialStatus,
            List<String> collectionMessages
    ) {
        GapRuleEngine.Evaluation evaluation = gapRuleEngine.evaluate(
                company, financials, disclosures, analyzedAt, disclosureStatus);
        List<GapResult> gaps = evaluation.findings().stream()
                .map(this::toGapResult)
                .toList();
        String message;
        if ("FAILURE".equals(collectionStatus)) {
            message = "자료 조회에 실패했습니다. 변경 근거가 없다고 판단할 수 없습니다.";
        } else if (!"SUCCESS".equals(collectionStatus)) {
            message = "일부 자료 조회에 실패했습니다. Gap 없음으로 해석하지 말고 조회 상태와 메시지를 확인하세요.";
        } else if (gaps.isEmpty()) {
            message = "조회 범위에서 직접 변경 근거를 찾지 못했습니다. 기존 RM 정보의 유효성이 보장되는 것은 아닙니다.";
        } else {
            message = gaps.size() + "개의 확인 항목을 찾았습니다. 우선순위와 근거를 확인하세요.";
        }
        return new GapAnalysisResponse(
                null,
                company,
                corpCode,
                analyzedAt,
                searchedFrom,
                collectionStatus,
                companyStatus,
                disclosureStatus,
                financialStatus,
                collectionMessages,
                financials,
                evaluation.financialSignals(),
                evaluation.assessments(),
                gaps,
                message
        );
    }

    private GapResult toGapResult(Finding finding) {
        var insight = openAiInsightService.generate(finding);
        String defaultReason = switch (finding.changeType()) {
            case "CONTRADICTION" -> "기존에 확인된 정보와 직접 공시 근거가 충돌합니다. 대상과 적용 시점을 상담으로 확인하세요.";
            case "NEW_INFORMATION" -> "기존 미확인·미기록 항목에서 새로운 계획의 직접 근거가 확인되었습니다.";
            case "PLAN_DETAIL_UPDATE" -> "기존 계획에 대한 직접 근거가 확인되어 미정 세부정보의 갱신이 필요합니다.";
            case "PLAN_TERMINATED" -> "기존 계획의 취소 또는 종료를 나타내는 직접 근거가 확인되었습니다.";
            default -> "공개정보와 RM 사전정보의 추가 확인이 필요합니다.";
        };
        return new GapResult(
                finding.type(),
                finding.existingInfo(),
                finding.latestInfo(),
                insight.map(OpenAiInsightService.Insight::reason).orElse(defaultReason),
                insight.isPresent() ? "OpenAI" : "규칙 템플릿",
                finding.evidence(),
                insight.map(OpenAiInsightService.Insight::questions)
                        .orElseGet(() -> questionsFor(finding.type())),
                finding.domain(),
                finding.planId(),
                finding.changeType(),
                finding.priority()
        );
    }

    private List<String> questionsFor(GapType type) {
        return switch (type) {
            case INVESTMENT_PLAN_GAP -> List.of(
                    "공시된 시설투자의 대상과 기존에 확인한 계획이 같은 건입니까?",
                    "집행 시기와 확정 금액, 변경된 내용은 무엇입니까?"
            );
            case FUNDING_PLAN_GAP -> List.of(
                    "공시된 자금조달의 목적과 필요한 규모는 어떻게 됩니까?",
                    "조달 일정과 상환 또는 후속 조달 계획이 있습니까?"
            );
            case FX_BUSINESS_GAP -> List.of(
                    "해외사업 계획의 대상 지역과 적용 기간은 어떻게 됩니까?",
                    "필요 외화 규모와 환위험 관리 계획은 무엇입니까?"
            );
        };
    }
}

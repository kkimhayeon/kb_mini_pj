package kb_bridge.agent;

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
                ? "현재 RM 사전정보와 확인 가능한 공개정보에서 규칙 기반 Gap을 찾지 못했습니다."
                : gaps.size() + "개의 고객정보 업데이트 후보를 확인했습니다.";
        return new GapAnalysisResponse(company, corpCode, financials, gaps, message);
    }

    private GapResult toGapResult(Finding finding) {
        var insight = geminiInsightService.generate(finding);
        return new GapResult(
                finding.type(),
                finding.existingInfo(),
                finding.latestInfo(),
                insight.map(GeminiInsightService.Insight::reason)
                        .orElse("기존 RM 상담정보와 최신 OpenDART 공개정보가 일치하지 않을 가능성이 있습니다."),
                insight.isPresent() ? "Gemini" : "Rule fallback",
                finding.evidence(),
                insight.map(GeminiInsightService.Insight::questions)
                        .orElseGet(() -> questionsFor(finding.type()))
        );
    }

    private List<String> questionsFor(GapType type) {
        return switch (type) {
            case INVESTMENT_PLAN_GAP -> List.of(
                    "공시된 투자 건의 실제 집행 일정과 필요한 자금 규모는 어떻게 됩니까?",
                    "투자 자금은 자체자금과 외부조달 중 어떤 방식으로 마련할 계획입니까?"
            );
            case FUNDING_PLAN_GAP -> List.of(
                    "최근 자금조달 또는 차입 변화의 목적과 필요 규모는 어떻게 됩니까?",
                    "상환 일정과 추가 자금조달 계획이 있습니까?"
            );
            case FX_BUSINESS_GAP -> List.of(
                    "해외사업 또는 해외법인 투자 계획의 현재 진행 단계와 일정은 어떻게 됩니까?",
                    "해외사업에 필요한 외화 규모와 환위험 관리 계획은 무엇입니까?"
            );
        };
    }
}

package kb_bridge.domain.company.entity;

import java.util.List;

public record GapAnalysisResponse(
        Company company, String corpCode, FinancialSnapshot financials, List<GapResult> gaps,
        String message, String outcome, List<SourceStatus> sources, boolean scopeLimited, String retrievalStatus,
        List<PlanComparison> comparisons, List<DisclosureEvidence> disclosures
) {
    public enum RetrievalState { SUCCESS, NO_DATA, NOT_REQUESTED, FAILED, PARTIAL }
    public record SourceStatus(String source, RetrievalState status, String message) { }

    public GapAnalysisResponse(Company company, String corpCode, FinancialSnapshot financials,
                               List<GapResult> gaps, String message) {
        this(company, corpCode, financials, gaps, message,
                gaps.isEmpty() ? "NO_CHANGE_EVIDENCE" : "GAPS_FOUND", List.of(), false, "SUCCESS", List.of(), List.of());
    }

    public GapAnalysisResponse withComparisons(List<PlanComparison> items) {
        return new GapAnalysisResponse(company, corpCode, financials, gaps, message, outcome, sources,
                scopeLimited, retrievalStatus, List.copyOf(items), disclosures);
    }

    public GapAnalysisResponse withDisclosures(List<DisclosureEvidence> items) {
        return new GapAnalysisResponse(company, corpCode, financials, gaps, message, outcome, sources,
                scopeLimited, retrievalStatus, comparisons, List.copyOf(items));
    }

    public GapAnalysisResponse withSources(List<SourceStatus> checks) {
        List<SourceStatus> evidence = checks.stream().filter(item -> !"COMPANY".equals(item.source())).toList();
        boolean failed = checks.stream().anyMatch(item -> item.status() == RetrievalState.FAILED);
        boolean partial = checks.stream().anyMatch(item -> item.status() == RetrievalState.PARTIAL);
        boolean missing = checks.stream().anyMatch(item -> item.status() == RetrievalState.NO_DATA);
        boolean usable = evidence.stream().anyMatch(item -> item.status() == RetrievalState.SUCCESS
                || item.status() == RetrievalState.PARTIAL);
        boolean limited = failed || partial || missing;
        String retrieval = failed ? (usable ? "PARTIAL_FAILURE" : "FAILURE")
                : partial ? "PARTIAL_FAILURE" : "SUCCESS";
        String result = !gaps.isEmpty() ? "GAPS_FOUND"
                : failed || partial ? "INCONCLUSIVE"
                : !usable ? "NO_DATA"
                : missing ? "INCONCLUSIVE" : "NO_CHANGE_EVIDENCE";
        String summary = switch (result) {
            case "GAPS_FOUND" -> gaps.size() + "개의 변경 후보를 확인했습니다."
                    + (limited ? " 일부 자료가 누락되어 확인된 범위에 한정한 결과입니다." : "");
            case "INCONCLUSIVE" -> "자료 조회 실패 또는 누락으로 변경 여부를 충분히 판단할 수 없습니다.";
            case "NO_DATA" -> "조회 범위에서 분석에 사용할 공개정보가 없습니다. 변경이 없다는 뜻은 아닙니다.";
            default -> "이번 조회 범위와 적용 규칙에서 변경 근거를 찾지 못했습니다. 변경이 없음을 확정하지는 않습니다.";
        };
        return new GapAnalysisResponse(company, corpCode, financials, gaps, summary,
                result, List.copyOf(checks), limited, retrieval, comparisons, disclosures);
    }
}

package kb_bridge.domain.company.entity;

import java.util.List;

public record AnalysisComparisonResponse(
        Long comparisonId,
        GapAnalysisResponse dbDartAnalysis,
        String dartOnlySummary,
        String dartOnlySource,
        List<ConsultationQuestion> dartOnlyQuestions,
        List<ConsultationQuestion> dbDartQuestions
) {
}

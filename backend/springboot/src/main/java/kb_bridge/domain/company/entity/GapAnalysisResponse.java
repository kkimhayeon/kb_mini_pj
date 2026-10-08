package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.util.List;

public record GapAnalysisResponse(
        Long analysisId,
        Company company,
        String corpCode,
        LocalDate analyzedAt,
        LocalDate searchedFrom,
        String collectionStatus,
        String companyStatus,
        String disclosureStatus,
        String financialStatus,
        List<String> collectionMessages,
        FinancialSnapshot financials,
        List<FinancialSignal> financialSignals,
        List<PlanAssessment> assessments,
        List<GapResult> gaps,
        String message
) {
}

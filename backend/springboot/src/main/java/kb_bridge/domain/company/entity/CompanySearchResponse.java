package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.util.List;

import kb_bridge.external.dart.dto.DartCompanyResponse;

public record CompanySearchResponse(
        String mode,
        String companyName,
        String corpCode,
        String message,
        Company company,
        AnalysisComparisonResponse comparison,
        DartCompanyResponse dartCompany,
        FinancialSnapshot financials,
        String financialStatus,
        String disclosureStatus,
        List<DisclosureEvidence> disclosures,
        List<String> collectionMessages,
        String aiStatus,
        String aiSummary,
        List<String> aiQuestions,
        LocalDate analyzedAt,
        LocalDate searchedFrom
) {
}

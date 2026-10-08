package kb_bridge.domain.company.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDate;
import java.util.ArrayList;

import kb_bridge.agent.GapAgent;
import kb_bridge.agent.OpenAiInsightService;
import kb_bridge.external.dart.DartClient;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.AnalysisComparisonResponse;
import kb_bridge.domain.company.entity.ConsultationQuestion;
import kb_bridge.domain.company.entity.DisclosureBatch;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.agent.GapAgent.ResearchPlan;
import kb_bridge.external.dart.dto.DartCompanyResponse;
import org.springframework.web.client.RestClientException;

@Service
public class CompanyService {

    private final DartClient dartClient;
    private final RmKnowledgeService rmKnowledgeService;
    private final GapAgent gapAgent;
    private final AnalysisHistoryService analysisHistoryService;
    private final OpenAiInsightService openAiInsightService;
    private final AnalysisComparisonService analysisComparisonService;

    public CompanyService(
            DartClient dartClient,
            RmKnowledgeService rmKnowledgeService,
            GapAgent gapAgent,
            AnalysisHistoryService analysisHistoryService,
            OpenAiInsightService openAiInsightService,
            AnalysisComparisonService analysisComparisonService
    ) {
        this.dartClient = dartClient;
        this.rmKnowledgeService = rmKnowledgeService;
        this.gapAgent = gapAgent;
        this.analysisHistoryService = analysisHistoryService;
        this.openAiInsightService = openAiInsightService;
        this.analysisComparisonService = analysisComparisonService;
    }

    public DartCompanyResponse getCompany(String corpCode) {
        return dartClient.getCompany(corpCode);
    }

    public List<Company> getRmCompanies() {
        return rmKnowledgeService.findAll();
    }

    public GapAnalysisResponse analyzeCompany(String companyId) {
        return runAnalysis(companyId).analysis();
    }

    public AnalysisComparisonResponse compareCompany(String companyId) {
        AnalysisRun run = runAnalysis(companyId);
        boolean hasDartResearchResults = isUsableDartResult(run.analysis().disclosureStatus())
                || isUsableDartResult(run.analysis().financialStatus());
        var dartOnlyInsight = hasDartResearchResults
                ? openAiInsightService.generateDartOnly(run.company(), run.financials(), run.disclosures())
                : java.util.Optional.<OpenAiInsightService.Insight>empty();
        String dartOnlySource = dartOnlyInsight.isPresent() ? "OPENAI" : "UNAVAILABLE";
        return analysisComparisonService.save(
                run.company(),
                run.analysis(),
                dartOnlyInsight.orElse(null),
                dartOnlySource
        );
    }

    public AnalysisComparisonResponse getLatestComparison(String companyId) {
        return analysisComparisonService.findLatest(companyId);
    }

    public ConsultationQuestion saveQuestionAnswer(long questionId, String answerText) {
        return analysisComparisonService.saveAnswer(questionId, answerText);
    }

    private AnalysisRun runAnalysis(String companyId) {
        Company company = rmKnowledgeService.findById(companyId);
        String corpCode = company.corpCode();
        LocalDate analyzedAt = LocalDate.now();
        List<String> collectionMessages = new ArrayList<>();
        String companyStatus = "FAILED";
        try {
            DartCompanyResponse dartCompany = dartClient.getCompany(corpCode);
            if (dartCompany != null && "000".equals(dartCompany.status())) {
                companyStatus = "SUCCESS";
            } else {
                String status = dartCompany == null ? "empty response" : dartCompany.status();
                String message = dartCompany == null || dartCompany.message() == null
                        ? ""
                        : ": " + dartCompany.message();
                collectionMessages.add("기업 기본정보 조회 실패 (OpenDART 상태: " + status + message + ").");
            }
        } catch (RestClientException | IllegalStateException exception) {
            collectionMessages.add(dartFailureMessage("기업 기본정보 조회", exception));
        }
        ResearchPlan researchPlan = gapAgent.planResearch(company);
        LocalDate searchedFrom = company.plans().stream()
                .map(plan -> plan.lastConfirmedAt() != null
                        ? plan.lastConfirmedAt()
                        : company.consultationDate() != null
                                ? company.consultationDate()
                                : analyzedAt.minusMonths(12))
                .min(LocalDate::compareTo)
                .orElse(analyzedAt.minusMonths(12));
        LocalDate requestFrom = searchedFrom.minusYears(1);
        FinancialSnapshot financials = null;
        String financialStatus = researchPlan.financialStatements() ? "NO_DATA" : "NOT_REQUESTED";
        if (researchPlan.financialStatements()) {
            try {
                financials = dartClient.getRecentFinancials(corpCode);
                if (financials != null) {
                    financialStatus = "SUCCESS";
                }
            } catch (RestClientException | IllegalStateException exception) {
                financialStatus = "FAILED";
                collectionMessages.add(dartFailureMessage("재무정보 조회", exception));
            }
        }

        List<DisclosureEvidence> disclosures = List.of();
        String disclosureStatus = researchPlan.disclosures() ? "FAILED" : "NOT_REQUESTED";
        if (researchPlan.disclosures()) {
            try {
                DisclosureBatch batch = dartClient.getRecentDisclosures(corpCode, requestFrom, analyzedAt);
                disclosures = batch.disclosures();
                collectionMessages.addAll(batch.collectionMessages());
                disclosureStatus = batch.collectionMessages().isEmpty() ? "SUCCESS" : "PARTIAL_FAILURE";
            } catch (RestClientException | IllegalStateException exception) {
                collectionMessages.add(dartFailureMessage("공시 목록 조회", exception));
            }
        }
        int requestedSources = 1
                + (researchPlan.disclosures() ? 1 : 0)
                + (researchPlan.financialStatements() ? 1 : 0);
        int successfulSources = ("SUCCESS".equals(companyStatus) ? 1 : 0)
                + (isSuccessfulRetrieval(disclosureStatus) ? 1 : 0)
                + (isSuccessfulRetrieval(financialStatus) ? 1 : 0);
        boolean hasRetrievalFailure = "FAILED".equals(companyStatus)
                || "FAILED".equals(financialStatus)
                || "FAILED".equals(disclosureStatus)
                || "PARTIAL_FAILURE".equals(disclosureStatus);
        String collectionStatus = !hasRetrievalFailure
                ? "SUCCESS"
                : successfulSources == 0 && requestedSources > 0
                        ? "FAILURE"
                        : "PARTIAL_FAILURE";
        GapAnalysisResponse analysis = gapAgent.analyze(
                company,
                corpCode,
                financials,
                disclosures,
                searchedFrom,
                analyzedAt,
                collectionStatus,
                companyStatus,
                disclosureStatus,
                financialStatus,
                List.copyOf(collectionMessages)
        );
        analysis = analysisHistoryService.save(company, analysis);
        rmKnowledgeService.updateAssessmentStatuses(companyId, analysis.assessments());
        return new AnalysisRun(company, analysis, financials, List.copyOf(disclosures));
    }

    private boolean isSuccessfulRetrieval(String status) {
        return "SUCCESS".equals(status) || "NO_DATA".equals(status) || "PARTIAL_FAILURE".equals(status);
    }

    private boolean isUsableDartResult(String status) {
        return "SUCCESS".equals(status) || "NO_DATA".equals(status) || "PARTIAL_FAILURE".equals(status);
    }

    private String dartFailureMessage(String operation, RuntimeException exception) {
        String details = exception.getMessage();
        if (details != null && (details.startsWith("OpenDART ") || details.startsWith("DART_API_KEY "))) {
            return operation + " 실패: " + details;
        }
        return operation + " 실패: OpenDART 연결, DART_API_KEY 형식과 사용 가능 여부를 확인하세요.";
    }

    private record AnalysisRun(
            Company company,
            GapAnalysisResponse analysis,
            FinancialSnapshot financials,
            List<DisclosureEvidence> disclosures
    ) {
    }
}
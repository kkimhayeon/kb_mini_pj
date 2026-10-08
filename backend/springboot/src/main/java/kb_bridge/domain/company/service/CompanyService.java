package kb_bridge.domain.company.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import kb_bridge.agent.GapAgent;
import kb_bridge.external.dart.DartClient;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.persistence.PlanFactEntity;
import kb_bridge.domain.company.persistence.PlanFactRepository;
import kb_bridge.agent.GapAgent.ResearchPlan;
import kb_bridge.external.dart.dto.DartCompanyResponse;

@Service
public class CompanyService {

    private final DartClient dartClient;
    private final RmKnowledgeService rmKnowledgeService;
    private final GapAgent gapAgent;
    private final GapAnalysisPersistenceService gapAnalysisPersistenceService;
    private final PlanFactRepository planFactRepository;

    public CompanyService(
            DartClient dartClient,
            RmKnowledgeService rmKnowledgeService,
            GapAgent gapAgent,
            GapAnalysisPersistenceService gapAnalysisPersistenceService,
            PlanFactRepository planFactRepository
    ) {
        this.dartClient = dartClient;
        this.rmKnowledgeService = rmKnowledgeService;
        this.gapAgent = gapAgent;
        this.gapAnalysisPersistenceService = gapAnalysisPersistenceService;
        this.planFactRepository = planFactRepository;
    }

    public DartCompanyResponse getCompany(String corpCode) {
        return dartClient.getCompany(corpCode);
    }

    public List<Company> getRmCompanies() {
        return rmKnowledgeService.findAll();
    }

    public GapAnalysisResponse analyzeCompany(String companyId) {
        Company baseCompany = rmKnowledgeService.findById(companyId);
        List<PlanFactEntity> confirmedFacts = confirmedFacts(companyId);
        Company company = applyConfirmedFacts(baseCompany, confirmedFacts);
        String corpCode = company.corpCode();
        if (corpCode == null || corpCode.isBlank()) {
            corpCode = dartClient.resolveCorpCode(company.companyName());
        }
        DartCompanyResponse dartCompany = dartClient.getCompany(corpCode);
        if (dartCompany == null || !"000".equals(dartCompany.status())) {
            String status = dartCompany == null ? "empty response" : dartCompany.status();
            String message = dartCompany == null ? "" : dartCompany.message();
            throw new IllegalStateException("OpenDART company lookup failed (" + status + "): " + message);
        }

        ResearchPlan researchPlan = gapAgent.planResearch(company);
        FinancialSnapshot financials = researchPlan.financialStatements()
                ? dartClient.getRecentFinancials(corpCode)
                : null;
        List<DisclosureEvidence> disclosures = researchPlan.disclosures()
                ? dartClient.getRecentDisclosures(corpCode)
                : List.of();
        GapAnalysisResponse response = gapAgent.analyze(company, corpCode, financials, disclosures);
        gapAnalysisPersistenceService.save(companyId, response, confirmedFacts);
        return response;
    }

    private List<PlanFactEntity> confirmedFacts(String companyId) {
        long id;
        try {
            id = Long.parseLong(companyId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid company id: " + companyId, e);
        }
        return planFactRepository.findByCompanyCompanyIdAndVerificationStatusOrderByFactIdDesc(id, "RM_CONFIRMED");
    }

    private Company applyConfirmedFacts(Company company, List<PlanFactEntity> confirmedFacts) {
        Map<String, PlanFactEntity> latestByDomain = confirmedFacts.stream()
                .collect(Collectors.toMap(
                        PlanFactEntity::getDomain,
                        fact -> fact,
                        (first, ignored) -> first
                ));
        return new Company(
                company.companyId(),
                company.corpCode(),
                company.companyName(),
                company.consultationDate(),
                planText(latestByDomain.get("INVESTMENT"), company.investmentPlan()),
                planText(latestByDomain.get("FUNDING"), company.fundingPlan()),
                planText(latestByDomain.get("FX"), company.foreignBusinessPlan()),
                company.rmMemo(),
                company.plans()
        );
    }

    private String planText(PlanFactEntity fact, String fallback) {
        if (fact == null) {
            return fallback;
        }
        return switch (fact.getPlanStatus()) {
            case "NOT_APPLICABLE" -> "없음";
            case "UNKNOWN" -> fallback;
            default -> fact.getScope() == null || fact.getScope().isBlank()
                    ? fact.getPlanStatus()
                    : fact.getScope();
        };
    }
}


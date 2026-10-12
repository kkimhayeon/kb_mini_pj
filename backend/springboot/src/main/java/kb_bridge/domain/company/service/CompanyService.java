package kb_bridge.domain.company.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import org.springframework.web.client.RestClientException;
import kb_bridge.domain.company.entity.GapAnalysisResponse.SourceStatus;
import kb_bridge.domain.company.entity.GapAnalysisResponse.RetrievalState;
import java.time.OffsetDateTime;

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
        Company company = ConfirmedPlanResolver.apply(baseCompany, confirmedFacts);
        List<SourceStatus> sources = new ArrayList<>();
        String corpCode = company.corpCode();
        try {
            if (corpCode == null || corpCode.isBlank()) {
                corpCode = dartClient.resolveCorpCode(company.companyName());
            }
            DartCompanyResponse dartCompany = dartClient.getCompany(corpCode);
            if (dartCompany == null || !"000".equals(dartCompany.status())) {
                throw new IllegalStateException("OpenDART company lookup failed.");
            }
            sources.add(new SourceStatus("COMPANY", RetrievalState.SUCCESS, "기업 기본정보를 확인했습니다."));
        } catch (RestClientException | IllegalStateException | IllegalArgumentException exception) {
            sources.add(new SourceStatus("COMPANY", RetrievalState.FAILED, "기업 기본정보를 확인하지 못했습니다. 다시 조회해주세요."));
            sources.add(new SourceStatus("FINANCIALS", RetrievalState.NOT_REQUESTED, "기업 확인 실패로 재무 조회를 진행하지 않았습니다."));
            sources.add(new SourceStatus("DISCLOSURES", RetrievalState.NOT_REQUESTED, "기업 확인 실패로 공시 조회를 진행하지 않았습니다."));
            var response = new GapAnalysisResponse(company, corpCode, null, List.of(), "").withSources(sources);
            gapAnalysisPersistenceService.save(companyId, response, confirmedFacts);
            return response;
        }

        ResearchPlan researchPlan = gapAgent.planResearch(company);
        FinancialSnapshot financials = null;
        if (researchPlan.financialStatements()) {
            try {
                financials = dartClient.getRecentFinancials(corpCode);
                RetrievalState state = financials == null ? RetrievalState.NO_DATA
                        : financials.revenue() == null || financials.operatingProfit() == null
                        || financials.shortTermDebt() == null || financials.priorPeriodShortTermDebt() == null
                        || financials.operatingCashFlow() == null ? RetrievalState.PARTIAL : RetrievalState.SUCCESS;
                sources.add(new SourceStatus("FINANCIALS", state, switch (state) {
                    case NO_DATA -> "조회 가능한 연간 재무정보가 없습니다.";
                    case PARTIAL -> "일부 재무 항목이 없어 비교 범위가 제한됩니다.";
                    default -> "연간 재무정보를 확인했습니다.";
                }));
            } catch (RestClientException | IllegalStateException exception) {
                sources.add(new SourceStatus("FINANCIALS", RetrievalState.FAILED, "재무정보 조회에 실패했습니다. 공시 등 확인된 자료만 사용합니다."));
            }
        } else {
            sources.add(new SourceStatus("FINANCIALS", RetrievalState.NOT_REQUESTED, "이번 조사 규칙에서는 재무정보를 요청하지 않았습니다."));
        }
        List<DisclosureEvidence> disclosures = List.of();
        if (researchPlan.disclosures()) {
            try {
                var batch = dartClient.getDisclosureBatch(corpCode);
                if (batch == null || batch.items() == null) {
                    throw new IllegalStateException("Invalid disclosure batch.");
                }
                disclosures = batch.items();
                if (batch.detailsIncomplete()) {
                    sources.add(new SourceStatus("DISCLOSURE_DETAILS", RetrievalState.PARTIAL,
                            "일부 양수 공시의 상세 항목을 확인하지 못해 금액·일정 비교가 제한됩니다."));
                }
                RetrievalState state = batch.truncated() ? RetrievalState.PARTIAL
                        : disclosures.isEmpty() ? RetrievalState.NO_DATA : RetrievalState.SUCCESS;
                sources.add(new SourceStatus("DISCLOSURES", state, switch (state) {
                    case PARTIAL -> "최근 1년 공시를 순회했지만 일부 페이지 또는 항목을 가져오지 못했습니다.";
                    case NO_DATA -> "최근 1년 조회 범위에 공시가 없습니다.";
                    default -> "최근 1년 공시의 전체 페이지를 확인했습니다.";
                }));
            } catch (RestClientException | IllegalStateException exception) {
                sources.add(new SourceStatus("DISCLOSURES", RetrievalState.FAILED, "공시 조회에 실패했습니다. 재무 등 확인된 자료만 사용합니다."));
            }
        } else {
            sources.add(new SourceStatus("DISCLOSURES", RetrievalState.NOT_REQUESTED, "이번 조사에서 공시를 요청하지 않았습니다."));
        }
        GapAnalysisResponse response = gapAgent.analyze(company, corpCode, financials, disclosures).withSources(sources);
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
        OffsetDateTime now = OffsetDateTime.now();
        return planFactRepository.findByCompanyCompanyIdAndVerificationStatusOrderByFactIdDesc(id, "RM_CONFIRMED")
                .stream()
                .filter(fact -> fact.getEffectiveFrom() == null || !fact.getEffectiveFrom().isAfter(now))
                .toList();
    }

}

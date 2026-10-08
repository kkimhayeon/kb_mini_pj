package kb_bridge.domain.company.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import kb_bridge.agent.OpenAiInsightService;
import kb_bridge.domain.company.entity.AnalysisComparisonResponse;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.CompanySearchResponse;
import kb_bridge.domain.company.entity.DisclosureBatch;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.external.dart.DartClient;
import kb_bridge.external.dart.dto.DartCompanyResponse;

@Service
public class CompanySearchService {

    private final DartClient dartClient;
    private final RmKnowledgeService rmKnowledgeService;
    private final CompanyService companyService;
    private final OpenAiInsightService openAiInsightService;

    public CompanySearchService(
            DartClient dartClient,
            RmKnowledgeService rmKnowledgeService,
            CompanyService companyService,
            OpenAiInsightService openAiInsightService
    ) {
        this.dartClient = dartClient;
        this.rmKnowledgeService = rmKnowledgeService;
        this.companyService = companyService;
        this.openAiInsightService = openAiInsightService;
    }

    public CompanySearchResponse search(String requestedName) {
        String companyName = requestedName == null ? "" : requestedName.trim();
        if (companyName.isBlank()) {
            throw new IllegalArgumentException("회사명을 입력하세요.");
        }
        if (companyName.length() > 200) {
            throw new IllegalArgumentException("회사명은 200자 이내로 입력하세요.");
        }

        String corpCode;
        try {
            corpCode = dartClient.resolveCorpCode(companyName);
        } catch (DartClient.CompanyNotFoundException exception) {
            return createGptReference(companyName);
        }

        Company company = rmKnowledgeService.findByCorpCode(corpCode).orElse(null);
        if (company != null) {
            AnalysisComparisonResponse comparison = companyService.compareCompany(company.companyId());
            return new CompanySearchResponse(
                    "RM_DART",
                    company.companyName(),
                    corpCode,
                    "OpenDART 공개정보와 RM DB 정보를 비교했습니다.",
                    company,
                    comparison,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "NOT_REQUESTED",
                    null,
                    List.of(),
                    comparison.dbDartAnalysis().analyzedAt(),
                    comparison.dbDartAnalysis().searchedFrom()
            );
        }

        return createDartOnlyResult(companyName, corpCode);
    }

    private CompanySearchResponse createDartOnlyResult(String requestedName, String corpCode) {
        DartCompanyResponse dartCompany = dartClient.getCompany(corpCode);
        if (dartCompany == null || !"000".equals(dartCompany.status())) {
            String status = dartCompany == null ? "empty response" : dartCompany.status();
            String message = dartCompany == null || dartCompany.message() == null
                    ? ""
                    : ": " + dartCompany.message();
            throw new IllegalStateException("OpenDART 기업정보 조회 실패 (" + status + ")" + message);
        }

        LocalDate analyzedAt = LocalDate.now();
        LocalDate searchedFrom = analyzedAt.minusYears(1);
        List<String> messages = new ArrayList<>();
        FinancialSnapshot financials = null;
        String financialStatus = "NO_DATA";
        try {
            financials = dartClient.getRecentFinancials(corpCode);
            if (financials != null) {
                financialStatus = "SUCCESS";
            }
        } catch (RestClientException | IllegalStateException exception) {
            financialStatus = "FAILED";
            messages.add("재무정보 조회 실패: " + safeFailureDetail(exception));
        }

        List<DisclosureEvidence> disclosures = List.of();
        String disclosureStatus = "FAILED";
        try {
            DisclosureBatch batch = dartClient.getRecentDisclosures(corpCode, searchedFrom, analyzedAt);
            disclosures = batch.disclosures();
            messages.addAll(batch.collectionMessages());
            disclosureStatus = batch.collectionMessages().isEmpty() ? "SUCCESS" : "PARTIAL_FAILURE";
        } catch (RestClientException | IllegalStateException exception) {
            messages.add("공시 조회 실패: " + safeFailureDetail(exception));
        }

        String displayName = dartCompany.corpName() == null ? requestedName : dartCompany.corpName();
        String resultMessage = "이 회사는 DART 기업정보에는 있지만 RM DB에는 없습니다. "
                + "공개정보만 조회했으며 RM 계획과의 Gap 비교는 수행하지 않았습니다.";
        return new CompanySearchResponse(
                "DART_ONLY",
                displayName,
                corpCode,
                resultMessage,
                null,
                null,
                dartCompany,
                financials,
                financialStatus,
                disclosureStatus,
                disclosures,
                List.copyOf(messages),
                "NOT_REQUESTED",
                null,
                List.of(),
                analyzedAt,
                searchedFrom
        );
    }

    private CompanySearchResponse createGptReference(String companyName) {
        String aiStatus = "NOT_CONFIGURED";
        String aiSummary = null;
        List<String> questions = List.of();
        List<String> messages = new ArrayList<>(List.of(
                "OpenDART에서 기업코드를 찾지 못했습니다. 회사가 존재하지 않는다는 뜻은 아니며, "
                        + "아래 GPT 응답은 DART로 검증되지 않았고 최신성이 보장되지 않습니다."
        ));
        try {
            var insight = openAiInsightService.generateCompanyReference(companyName);
            if (insight.isPresent()) {
                aiStatus = "OPENAI";
                aiSummary = insight.get().reason();
                questions = insight.get().questions();
            }
        } catch (RestClientException | IllegalStateException exception) {
            aiStatus = "FAILED";
            messages.add("OpenAI 참고정보 생성 실패: " + safeFailureDetail(exception));
        }

        return new CompanySearchResponse(
                aiStatus.equals("OPENAI") ? "GPT_REFERENCE" : "GPT_UNAVAILABLE",
                companyName,
                null,
                aiStatus.equals("OPENAI")
                        ? "GPT가 생성한 참고정보입니다. 회사별 사실, 정확성, 최신성은 별도로 확인하세요."
                        : "OpenAI를 사용할 수 없어 GPT 참고정보를 생성하지 못했습니다. OPEN_API_KEY 설정과 연결 상태를 확인하세요.",
                null,
                null,
                null,
                null,
                "NOT_REQUESTED",
                "NOT_REQUESTED",
                List.of(),
                List.copyOf(messages),
                aiStatus,
                aiSummary,
                questions,
                LocalDate.now(),
                null
        );
    }

    private String safeFailureDetail(RuntimeException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String sanitized = detail
                .replaceAll("(?i)(crtfc_key=)[^&\\s]+", "$1[redacted]")
                .replaceAll("[0-9a-fA-F]{40}", "[redacted]")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.substring(0, Math.min(sanitized.length(), 240));
    }
}

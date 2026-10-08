package kb_bridge.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import kb_bridge.agent.OpenAiInsightService;
import kb_bridge.domain.company.entity.AnalysisComparisonResponse;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureBatch;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.external.dart.DartClient;
import kb_bridge.external.dart.dto.DartCompanyResponse;

class CompanySearchServiceTest {

    private final DartClient dartClient = mock(DartClient.class);
    private final RmKnowledgeService rmKnowledgeService = mock(RmKnowledgeService.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final OpenAiInsightService openAiInsightService = mock(OpenAiInsightService.class);
    private final CompanySearchService service = new CompanySearchService(
            dartClient, rmKnowledgeService, companyService, openAiInsightService);

    @Test
    void runsExistingComparisonWhenCompanyExistsInDartAndRmDatabase() {
        Company company = new Company("42", "00123456", "한빛전자", null, List.of(), null);
        GapAnalysisResponse analysis = new GapAnalysisResponse(
                10L, company, company.corpCode(), LocalDate.now(), LocalDate.now().minusYears(1),
                "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", List.of(), null, List.of(), List.of(),
                List.of(), "분석 완료");
        AnalysisComparisonResponse comparison = new AnalysisComparisonResponse(
                3L, analysis, "OpenAI 요약", "OPENAI", List.of(), List.of());
        when(dartClient.resolveCorpCode("한빛전자")).thenReturn(company.corpCode());
        when(rmKnowledgeService.findByCorpCode(company.corpCode())).thenReturn(Optional.of(company));
        when(companyService.compareCompany(company.companyId())).thenReturn(comparison);

        var result = service.search(" 한빛전자 ");

        assertThat(result.mode()).isEqualTo("RM_DART");
        assertThat(result.company()).isEqualTo(company);
        assertThat(result.comparison()).isEqualTo(comparison);
        verify(companyService).compareCompany("42");
        verifyNoInteractions(openAiInsightService);
    }

    @Test
    void providesDartPublicInformationWhenCompanyIsMissingFromRmDatabase() {
        DartCompanyResponse dartCompany = new DartCompanyResponse(
                "000", "정상", "00999999", "DART 등록 회사",
                null, "등록 회사", "12345", "대표자", "K", null, null, "서울",
                "https://example.test", null, null, null, null, "20000101", "12");
        FinancialSnapshot financials = new FinancialSnapshot(
                "2025", null, null, null, null, null, null, null);
        when(dartClient.resolveCorpCode("DART 등록 회사")).thenReturn("00999999");
        when(rmKnowledgeService.findByCorpCode("00999999")).thenReturn(Optional.empty());
        when(dartClient.getCompany("00999999")).thenReturn(dartCompany);
        when(dartClient.getRecentFinancials("00999999")).thenReturn(financials);
        when(dartClient.getRecentDisclosures("00999999", LocalDate.now().minusYears(1), LocalDate.now()))
                .thenReturn(new DisclosureBatch(List.of(), List.of()));

        var result = service.search("DART 등록 회사");

        assertThat(result.mode()).isEqualTo("DART_ONLY");
        assertThat(result.corpCode()).isEqualTo("00999999");
        assertThat(result.dartCompany()).isEqualTo(dartCompany);
        assertThat(result.financials()).isEqualTo(financials);
        assertThat(result.aiStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(result.aiSummary()).isNull();
        verifyNoInteractions(openAiInsightService);
        verify(companyService, org.mockito.Mockito.never()).compareCompany(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void usesClearlyLabeledGptReferenceWhenDartHasNoMatchingCompany() {
        when(dartClient.resolveCorpCode("비상장 회사")).thenThrow(
                new DartClient.CompanyNotFoundException("비상장 회사"));
        when(openAiInsightService.generateCompanyReference("비상장 회사"))
                .thenReturn(Optional.of(new OpenAiInsightService.Insight("일반 참고 설명", List.of("공식 자료를 확인하세요"))));

        var result = service.search("비상장 회사");

        assertThat(result.mode()).isEqualTo("GPT_REFERENCE");
        assertThat(result.corpCode()).isNull();
        assertThat(result.message()).contains("최신성");
        assertThat(result.collectionMessages()).anySatisfy(
                message -> assertThat(message).contains("검증되지 않았고"));
        assertThat(result.aiSummary()).isEqualTo("일반 참고 설명");
        assertThat(result.aiQuestions()).containsExactly("공식 자료를 확인하세요");
        verifyNoInteractions(companyService, rmKnowledgeService);
    }
}

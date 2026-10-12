package kb_bridge.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.ObjectMapper;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.GapAnalysisResponse.SourceStatus;
import kb_bridge.domain.company.entity.GapAnalysisResponse.RetrievalState;
import kb_bridge.domain.company.persistence.RmPlanRepository;

class GapAnalysisPersistenceServiceTest {
    @ParameterizedTest
    @CsvSource({"FAILED,FAILURE,true", "PARTIAL,PARTIAL_FAILURE,true", "NO_DATA,SUCCESS,true", "SUCCESS,SUCCESS,false"})
    void storesRealRetrievalStateAndCoverageSnapshot(RetrievalState state, String storedStatus, boolean limited) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(call -> {
            call.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            call.getArgument(1, KeyHolder.class).getKeyList().add(Map.of("analysis_id", 1L));
            return 1;
        });
        var mapper = new ObjectMapper();
        var service = new GapAnalysisPersistenceService(jdbc, mapper, mock(RmPlanRepository.class));
        var company = new Company("1", "테스트기업", LocalDate.parse("2026-01-01"), "없음", "없음", "없음", "");
        var response = new GapAnalysisResponse(company, null, null, List.of(), "")
                .withSources(List.of(new SourceStatus("DISCLOSURES", state, "조회 상태")));
        service.save("1", response, List.of());
        verify(statement).setString(3, storedStatus);
        verify(statement).setBoolean(8, limited);
        var snapshot = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(4), snapshot.capture());
        var json = mapper.readTree(snapshot.getValue());
        assertThat(json.path("sources").path(0).path("status").asText()).isEqualTo(state.name());
        assertThat(json.path("outcome").asText()).isEqualTo(response.outcome());
    }

    @org.junit.jupiter.api.Test
    void storesExplicitPlanAndNumericChangesWithoutChoosingADomainDefault() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var connection = mock(Connection.class);
        var analysisStatement = mock(PreparedStatement.class);
        var resultStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("INSERT INTO gap_analysis"), any(String[].class))).thenReturn(analysisStatement);
        when(connection.prepareStatement(contains("INSERT INTO gap_result"), any(String[].class))).thenReturn(resultStatement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(call -> {
            call.getArgument(0, PreparedStatementCreator.class).createPreparedStatement(connection);
            call.getArgument(1, KeyHolder.class).getKeyList().add(Map.of("id", 1L));
            return 1;
        });
        var mapper = new ObjectMapper();
        var plans = mock(RmPlanRepository.class);
        var service = new GapAnalysisPersistenceService(jdbc, mapper, plans);
        var company = new Company("1", "테스트기업", LocalDate.parse("2026-01-01"), "예정", "없음", "없음", "");
        var changes = List.of(new kb_bridge.domain.company.entity.PlanComparison.FieldChange("AMOUNT", "100 KRW", "150 KRW"));
        var gap = new kb_bridge.domain.company.entity.GapResult(
                kb_bridge.rule.GapRuleEngine.GapType.INVESTMENT_PLAN_GAP, "NEEDS_CONFIRMATION", "PENDING",
                "추가 확인", "MEDIUM", "기존", "변경", "동일 사업 확인 필요", "Rule fallback",
                List.of(), List.of("금액 확인"), 12L, changes);
        service.save("1", new GapAnalysisResponse(company, "00000001", null, List.of(gap), ""), List.of());
        verify(resultStatement).setLong(3, 12L);
        var captured = ArgumentCaptor.forClass(String.class);
        verify(resultStatement).setString(eq(9), captured.capture());
        var json = mapper.readTree(captured.getValue()).path(0);
        assertThat(json.path("before").asText()).isEqualTo("100 KRW");
        assertThat(json.path("after").asText()).isEqualTo("150 KRW");
        verifyNoInteractions(plans);
    }
}

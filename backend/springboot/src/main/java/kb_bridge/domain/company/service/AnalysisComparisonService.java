package kb_bridge.domain.company.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.AnalysisComparisonResponse;
import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.ConsultationQuestion;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.GapResult;
import kb_bridge.agent.OpenAiInsightService.Insight;

@Service
public class AnalysisComparisonService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisComparisonService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AnalysisComparisonResponse save(
            Company company,
            GapAnalysisResponse dbDartAnalysis,
            Insight dartOnlyInsight,
            String dartOnlySource
    ) {
        String summary = dartOnlyInsight == null
                ? "OpenDART 공개자료 또는 OpenAI 응답을 확보하지 못해 요약을 생성하지 못했습니다. 아래 조회 상태와 메시지를 확인하세요."
                : dartOnlyInsight.reason();
        String analysisSnapshot = toJson(dbDartAnalysis);
        Long comparisonId = jdbcTemplate.queryForObject("""
                INSERT INTO analysis_comparison (
                    company_id, analysis_id, dart_only_summary, dart_only_source, db_dart_snapshot
                ) VALUES (?, ?, ?, ?, ?::jsonb)
                RETURNING comparison_id
                """,
                Long.class,
                Long.parseLong(company.companyId()),
                dbDartAnalysis.analysisId(),
                summary,
                dartOnlySource,
                analysisSnapshot
        );
        if (comparisonId == null) {
            throw new IllegalStateException("Database did not return the saved comparison id.");
        }

        List<ConsultationQuestion> dartOnlyQuestions = new ArrayList<>();
        if (dartOnlyInsight != null) {
            for (int index = 0; index < dartOnlyInsight.questions().size(); index++) {
                dartOnlyQuestions.add(insertQuestion(
                        comparisonId,
                        "AI_DART",
                        null,
                        index,
                        dartOnlyInsight.questions().get(index),
                        "OPENAI"
                ));
            }
        }
        List<ConsultationQuestion> dbDartQuestions = new ArrayList<>();
        int index = 0;
        for (GapResult gap : dbDartAnalysis.gaps()) {
            for (String question : gap.questions()) {
                Long planId = gap.planId() == null ? null : Long.parseLong(gap.planId());
                dbDartQuestions.add(insertQuestion(
                        comparisonId,
                        "DB_DART",
                        planId,
                        index++,
                        question,
                        "OpenAI".equals(gap.explanationSource()) ? "OPENAI" : "RULE_TEMPLATE"
                ));
            }
        }
        return new AnalysisComparisonResponse(
                comparisonId,
                dbDartAnalysis,
                summary,
                dartOnlySource,
                List.copyOf(dartOnlyQuestions),
                List.copyOf(dbDartQuestions)
        );
    }

    public AnalysisComparisonResponse findLatest(String companyId) {
        List<ComparisonSnapshot> snapshots = jdbcTemplate.query("""
                SELECT comparison_id, db_dart_snapshot::text, dart_only_summary, dart_only_source
                FROM analysis_comparison
                WHERE company_id = ?
                ORDER BY analyzed_at DESC, comparison_id DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new ComparisonSnapshot(
                        resultSet.getLong("comparison_id"),
                        resultSet.getString("db_dart_snapshot"),
                        resultSet.getString("dart_only_summary"),
                        resultSet.getString("dart_only_source")
                ),
                Long.parseLong(companyId)
        );
        if (snapshots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No comparison has been saved for this company.");
        }
        ComparisonSnapshot snapshot = snapshots.get(0);
        GapAnalysisResponse analysis;
        try {
            analysis = objectMapper.readValue(snapshot.dbDartSnapshot(), GapAnalysisResponse.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Saved comparison contains an invalid analysis snapshot.", exception);
        }
        if (analysis.company() == null
                || analysis.corpCode() == null
                || analysis.analyzedAt() == null
                || analysis.assessments() == null
                || analysis.gaps() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This saved comparison uses an older incomplete snapshot. Run a new comparison to refresh it."
            );
        }
        return new AnalysisComparisonResponse(
                snapshot.comparisonId(),
                analysis,
                snapshot.dartOnlySummary(),
                snapshot.dartOnlySource(),
                findQuestions(snapshot.comparisonId(), "AI_DART"),
                findQuestions(snapshot.comparisonId(), "DB_DART")
        );
    }

    @Transactional
    public ConsultationQuestion saveAnswer(long questionId, String answerText) {
        if (answerText == null || answerText.isBlank()) {
            throw new IllegalArgumentException("Answer text must not be blank.");
        }
        String normalizedAnswer = answerText.trim();
        if (normalizedAnswer.length() > 10000) {
            throw new IllegalArgumentException("Answer text must not exceed 10000 characters.");
        }
        List<Long> savedAnswers = jdbcTemplate.query("""
                INSERT INTO consultation_answer (question_id, answer_text)
                SELECT question_id, ?
                FROM consultation_question
                WHERE question_id = ?
                ON CONFLICT (question_id) DO UPDATE
                SET answer_text = EXCLUDED.answer_text,
                    answered_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING question_id, answer_text, answered_at
                """,
                (resultSet, rowNumber) -> resultSet.getLong("question_id"),
                normalizedAnswer,
                questionId
        );
        if (savedAnswers.isEmpty()) {
            throw new IllegalArgumentException("Unknown consultation question id: " + questionId);
        }
        return jdbcTemplate.queryForObject("""
                SELECT q.question_id, q.question_text, q.plan_id, a.answer_text, a.answered_at
                FROM consultation_question q
                JOIN consultation_answer a ON a.question_id = q.question_id
                WHERE q.question_id = ?
                """,
                (resultSet, rowNumber) -> new ConsultationQuestion(
                        resultSet.getLong("question_id"),
                        resultSet.getString("question_text"),
                        resultSet.getString("answer_text"),
                        localDateTime(resultSet.getTimestamp("answered_at")),
                        nullablePlanId(resultSet.getLong("plan_id"), resultSet.wasNull())
                ),
                questionId
        );
    }

    private ConsultationQuestion insertQuestion(
            long comparisonId,
            String mode,
            Long planId,
            int displayOrder,
            String questionText,
            String generationSource
    ) {
        Long questionId = jdbcTemplate.queryForObject("""
                INSERT INTO consultation_question (
                    comparison_id, analysis_mode, plan_id, display_order, question_text, generation_source
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING question_id
                """,
                Long.class,
                comparisonId,
                mode,
                planId,
                displayOrder,
                questionText,
                generationSource
        );
        if (questionId == null) {
            throw new IllegalStateException("Database did not return the saved consultation question id.");
        }
        return new ConsultationQuestion(questionId, questionText, null, null, planId == null ? null : planId.toString());
    }

    private List<ConsultationQuestion> findQuestions(long comparisonId, String mode) {
        return jdbcTemplate.query("""
                SELECT q.question_id, q.question_text, q.plan_id, a.answer_text, a.answered_at
                FROM consultation_question q
                LEFT JOIN consultation_answer a ON a.question_id = q.question_id
                WHERE q.comparison_id = ? AND q.analysis_mode = ?
                ORDER BY q.display_order, q.question_id
                """,
                (resultSet, rowNumber) -> new ConsultationQuestion(
                        resultSet.getLong("question_id"),
                        resultSet.getString("question_text"),
                        resultSet.getString("answer_text"),
                        localDateTime(resultSet.getTimestamp("answered_at")),
                        nullablePlanId(resultSet.getLong("plan_id"), resultSet.wasNull())
                ),
                comparisonId,
                mode
        );
    }

    private String nullablePlanId(long planId, boolean wasNull) {
        return wasNull ? null : Long.toString(planId);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize comparison history.", exception);
        }
    }

    private record ComparisonSnapshot(
            Long comparisonId,
            String dbDartSnapshot,
            String dartOnlySummary,
            String dartOnlySource
    ) {
    }
}

package kb_bridge.domain.company.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSignal;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.GapResult;
import kb_bridge.domain.company.entity.PlanAssessment;

@Service
public class AnalysisHistoryService {

    private static final String RULE_VERSION = "gap-rules-v2";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisHistoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GapAnalysisResponse save(Company company, GapAnalysisResponse response) {
        long companyId = parseId(company.companyId());
        Map<String, Object> snapshotData = new HashMap<>();
        snapshotData.put("capturedAt", response.analyzedAt());
        snapshotData.put("company", company);
        snapshotData.put("searchedFrom", response.searchedFrom());
        snapshotData.put("planQueryRanges", company.plans().stream().map(plan -> Map.of(
                "planId", plan.planId(),
                "from", plan.lastConfirmedAt() != null
                        ? plan.lastConfirmedAt()
                        : company.consultationDate() != null
                                ? company.consultationDate()
                                : response.analyzedAt().minusMonths(12),
                "to", response.analyzedAt()
        )).toList());
        String snapshot = toJson(snapshotData);
        Long analysisId = insertAnalysis(companyId, response, snapshot);

        Map<String, GapResult> gapsByPlanId = new HashMap<>();
        for (GapResult gap : response.gaps()) {
            gapsByPlanId.put(gap.planId(), gap);
        }
        for (PlanAssessment assessment : response.assessments()) {
            GapResult gap = gapsByPlanId.get(assessment.planId());
            Long planId = assessment.planId() == null ? null : parseId(assessment.planId());
            long resultId = insertResult(analysisId, planId, assessment, gap, response.financialSignals());
            for (DisclosureEvidence evidence : assessment.evidence()) {
                insertEvidence(resultId, evidence);
            }
        }
        return new GapAnalysisResponse(
                analysisId,
                response.company(),
                response.corpCode(),
                response.analyzedAt(),
                response.searchedFrom(),
                response.collectionStatus(),
                response.companyStatus(),
                response.disclosureStatus(),
                response.financialStatus(),
                response.collectionMessages(),
                response.financials(),
                response.financialSignals(),
                response.assessments(),
                response.gaps(),
                response.message()
        );
    }

    private Long insertAnalysis(long companyId, GapAnalysisResponse response, String snapshot) {
        String sql = """
                INSERT INTO gap_analysis (
                    company_id, analysis_at, query_start_at, query_end_at, retrieval_status,
                    crm_snapshot, rule_version, collection_messages, company_status,
                    disclosure_status, financial_status
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                """;
        Long key = jdbcTemplate.queryForObject(
                sql + " RETURNING analysis_id",
                Long.class,
                companyId,
                timestamp(response.analyzedAt()),
                timestamp(response.searchedFrom()),
                timestamp(response.analyzedAt()),
                response.collectionStatus(),
                snapshot,
                RULE_VERSION,
                toJson(response.collectionMessages()),
                response.companyStatus(),
                response.disclosureStatus(),
                response.financialStatus()
        );
        if (key == null) {
            throw new IllegalStateException("Database did not return the saved analysis id.");
        }
        return key;
    }

    private long insertResult(
            long analysisId,
            Long planId,
            PlanAssessment assessment,
            GapResult gap,
            List<FinancialSignal> financialSignals
    ) {
        String sql = """
                INSERT INTO gap_result (
                    analysis_id, plan_id, domain, change_type, assessment_status, priority,
                    priority_reason, changed_fields, financial_signals, questions,
                    status_summary, compared_from
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                """;
        Integer priority = priorityToDatabase(assessment.priority());
        Long key = jdbcTemplate.queryForObject(
                sql + " RETURNING result_id",
                Long.class,
                analysisId,
                planId,
                domainToDatabase(assessment.domain()),
                changeTypeToDatabase(assessment.changeType()),
                assessmentStatusToDatabase(assessment.status()),
                priority,
                assessment.summary(),
                toJson(changedFields(assessment, gap)),
                toJson(financialSignals),
                toJson(gap == null ? List.of() : gap.questions()),
                assessment.summary(),
                assessment.comparedFrom()
        );
        if (key == null) {
            throw new IllegalStateException("Database did not return the saved analysis result id.");
        }
        return key;
    }

    private void insertEvidence(long resultId, DisclosureEvidence evidence) {
        if (evidence.receiptNumber() == null || evidence.receiptNumber().isBlank()) {
            return;
        }
        String correctionDetails = evidence.correctionStatus() == null
                ? null
                : toJson(Map.of("status", evidence.correctionStatus()));
        jdbcTemplate.update("""
                INSERT INTO analysis_evidence (
                    result_id, rcept_no, report_name, disclosed_at, source_url, evidence_text,
                    evidence_location, original_rcept_no, correction_details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (result_id, rcept_no) DO NOTHING
                """,
                resultId,
                evidence.receiptNumber(),
                evidence.title(),
                parseDateTime(evidence.date()),
                evidence.url(),
                evidence.excerpt(),
                null,
                null,
                correctionDetails
        );
    }

    private Map<String, Object> changedFields(PlanAssessment assessment, GapResult gap) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("scope", assessment.scope());
        fields.put("changeType", assessment.changeType());
        if (gap != null) {
            fields.put("existingInfo", gap.existingInfo());
            fields.put("latestInfo", gap.latestInfo());
        }
        return fields;
    }

    private String domainToDatabase(String domain) {
        return "FOREIGN_BUSINESS".equals(domain) ? "FX" : domain;
    }

    private String changeTypeToDatabase(String changeType) {
        return switch (changeType) {
            case "CONTRADICTION" -> "CONFLICT";
            case "NEW_INFORMATION" -> "NEW_INFO";
            case "PLAN_DETAIL_UPDATE" -> "SPECIFIED";
            case "PLAN_TERMINATED" -> "CANCELED_OR_COMPLETED";
            case "ADDITIONAL_CONFIRMATION_REQUIRED" -> "NEEDS_CONFIRMATION";
            default -> null;
        };
    }

    private String assessmentStatusToDatabase(String status) {
        return switch (status) {
            case "ACTION_REQUIRED" -> "CONFIRMED";
            case "JUDGMENT_PENDING" -> "PENDING";
            case "CHANGE_EVIDENCE_NOT_FOUND" -> "NO_CHANGE_EVIDENCE";
            case "RECONFIRM_REQUIRED" -> "RECHECK_NEEDED";
            case "RETRIEVAL_FAILED" -> "RETRIEVAL_FAILED";
            default -> "PENDING";
        };
    }

    private Integer priorityToDatabase(String priority) {
        return switch (priority) {
            case "PRIORITY_1" -> 1;
            case "PRIORITY_2" -> 2;
            case "PRIORITY_3" -> 3;
            default -> null;
        };
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a database-generated numeric id.", exception);
        }
    }

    private Timestamp timestamp(LocalDate date) {
        return Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private Timestamp parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return timestamp(LocalDate.parse(value));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize analysis history JSON.", exception);
        }
    }
}

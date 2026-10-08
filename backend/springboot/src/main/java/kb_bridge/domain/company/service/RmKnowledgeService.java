package kb_bridge.domain.company.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.PlanAssessment;
import kb_bridge.domain.company.entity.PlanKnowledge;

@Service
public class RmKnowledgeService {

    private static final String FIND_ALL_SQL = """
            SELECT c.company_id, c.corp_code, c.corp_name, c.consultation_date, c.rm_memo,
                   p.plan_id, p.domain, p.scope, p.knowledge_status, p.plan_status,
                   p.last_confirmed_at, p.amount, p.currency, p.expected_at_label,
                   p.expected_at_precision, p.source, p.evidence_level, p.source_reference,
                   p.valid_until, p.review_due_at, p.validity_status, p.recorded_at, p.note
            FROM company c
            LEFT JOIN rm_plan p ON p.company_id = c.company_id
            WHERE c.is_active = TRUE
            ORDER BY c.company_id, p.domain, p.plan_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public RmKnowledgeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Company> findAll() {
        List<CompanyRow> rows = jdbcTemplate.query(FIND_ALL_SQL, (resultSet, rowNumber) -> {
            long planId = resultSet.getLong("plan_id");
            PlanKnowledge plan = resultSet.wasNull() ? null : new PlanKnowledge(
                    Long.toString(planId),
                    domainToApi(resultSet.getString("domain")),
                    resultSet.getString("scope"),
                    knowledgeStatusToApi(resultSet.getString("knowledge_status")),
                    planStatusToApi(resultSet.getString("plan_status")),
                    dateTimeToDate(resultSet.getTimestamp("last_confirmed_at")),
                    resultSet.getBigDecimal("amount"),
                    resultSet.getString("currency"),
                    resultSet.getString("expected_at_label"),
                    resultSet.getString("expected_at_precision"),
                    resultSet.getString("source"),
                    evidenceLevelToApi(resultSet.getString("evidence_level")),
                    resultSet.getString("source_reference"),
                    date(resultSet.getDate("valid_until")),
                    date(resultSet.getDate("review_due_at")),
                    validityStatusToApi(resultSet.getString("validity_status")),
                    dateTimeToDate(resultSet.getTimestamp("recorded_at")),
                    resultSet.getString("note")
            );
            return new CompanyRow(
                    Long.toString(resultSet.getLong("company_id")),
                    resultSet.getString("corp_code"),
                    resultSet.getString("corp_name"),
                    date(resultSet.getDate("consultation_date")),
                    resultSet.getString("rm_memo"),
                    plan
            );
        });

        Map<String, CompanyBuilder> companies = new LinkedHashMap<>();
        for (CompanyRow row : rows) {
            CompanyBuilder company = companies.computeIfAbsent(row.companyId(), ignored -> new CompanyBuilder(
                    row.companyId(), row.corpCode(), row.companyName(), row.consultationDate(), row.rmMemo()));
            if (row.plan() != null) {
                company.addPlan(row.plan());
            }
        }
        return companies.values().stream().map(CompanyBuilder::build).toList();
    }

    public Company findById(String companyId) {
        return findAll().stream()
                .filter(company -> company.companyId().equals(companyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown RM company id: " + companyId));
    }

    public void updateAssessmentStatuses(String companyId, List<PlanAssessment> assessments) {
        long databaseCompanyId = parseDatabaseId(companyId);
        for (PlanAssessment assessment : assessments) {
            String validityStatus = switch (assessment.status()) {
                case "CHANGE_EVIDENCE_NOT_FOUND" -> "NO_CHANGE_EVIDENCE";
                case "RECONFIRM_REQUIRED" -> "RECHECK_NEEDED";
                case "JUDGMENT_PENDING", "RETRIEVAL_FAILED", "ACTION_REQUIRED" -> "PENDING";
                default -> null;
            };
            if (validityStatus != null) {
                jdbcTemplate.update("""
                        UPDATE rm_plan
                        SET validity_status = ?
                        WHERE company_id = ? AND plan_id = ?
                        """, validityStatus, databaseCompanyId, parseDatabaseId(assessment.planId()));
            }
        }
    }

    private long parseDatabaseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a database-generated numeric id.", exception);
        }
    }

    private String domainToApi(String domain) {
        return "FX".equals(domain) ? "FOREIGN_BUSINESS" : domain;
    }

    private String knowledgeStatusToApi(String status) {
        return switch (status) {
            case "EXPLICIT_NONE" -> "EXPLICIT_NO";
            case "UNCONFIRMED" -> "UNKNOWN";
            default -> status;
        };
    }

    private String planStatusToApi(String status) {
        return switch (status) {
            case "REVIEW" -> "UNDER_REVIEW";
            case "CANCELED" -> "CANCELLED";
            default -> status;
        };
    }

    private String evidenceLevelToApi(String level) {
        return "DIRECT".equals(level) ? "DIRECT_CONFIRMATION" : level;
    }

    private String validityStatusToApi(String status) {
        return switch (status) {
            case "NO_CHANGE_EVIDENCE" -> "CHANGE_NOT_FOUND";
            case "RECHECK_NEEDED" -> "RECONFIRM_REQUIRED";
            case "PENDING" -> "JUDGMENT_PENDING";
            default -> status;
        };
    }

    private LocalDate date(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalDate dateTimeToDate(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate();
    }

    private record CompanyRow(
            String companyId,
            String corpCode,
            String companyName,
            LocalDate consultationDate,
            String rmMemo,
            PlanKnowledge plan
    ) {
    }

    private static final class CompanyBuilder {
        private final String companyId;
        private final String corpCode;
        private final String companyName;
        private final LocalDate consultationDate;
        private final String rmMemo;
        private final List<PlanKnowledge> plans = new ArrayList<>();

        private CompanyBuilder(
                String companyId,
                String corpCode,
                String companyName,
                LocalDate consultationDate,
                String rmMemo
        ) {
            this.companyId = companyId;
            this.corpCode = corpCode;
            this.companyName = companyName;
            this.consultationDate = consultationDate;
            this.rmMemo = rmMemo;
        }

        private void addPlan(PlanKnowledge plan) {
            plans.add(plan);
        }

        private Company build() {
            return new Company(companyId, corpCode, companyName, consultationDate, List.copyOf(plans), rmMemo);
        }
    }
}

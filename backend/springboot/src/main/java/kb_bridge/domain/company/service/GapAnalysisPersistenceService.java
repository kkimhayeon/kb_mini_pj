package kb_bridge.domain.company.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.DisclosureEvidence;
import kb_bridge.domain.company.entity.FinancialSnapshot;
import kb_bridge.domain.company.entity.GapAnalysisResponse;
import kb_bridge.domain.company.entity.GapResult;
import kb_bridge.domain.company.persistence.PlanFactEntity;
import kb_bridge.domain.company.persistence.RmPlanEntity;
import kb_bridge.domain.company.persistence.RmPlanRepository;
import kb_bridge.rule.GapRuleEngine.GapType;

@Service
public class GapAnalysisPersistenceService {

    private static final Pattern RCEPT_NO = Pattern.compile("rcpNo=([0-9]{14})");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RmPlanRepository rmPlanRepository;

    public GapAnalysisPersistenceService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RmPlanRepository rmPlanRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rmPlanRepository = rmPlanRepository;
    }

    @Transactional
    public void save(String companyId, GapAnalysisResponse response, List<PlanFactEntity> confirmedFacts) {
        long companyPk = parseCompanyId(companyId);
        OffsetDateTime analysisAt = OffsetDateTime.now();
        Map<String, RmPlanEntity> plansByDomain = rmPlanRepository.findByCompanyCompanyId(companyPk).stream()
                .collect(Collectors.toMap(RmPlanEntity::getDomain, plan -> plan, (first, ignored) -> first));

        long analysisId = insertAnalysis(companyPk, analysisAt, response, confirmedFacts);
        for (GapResult gap : response.gaps()) {
            Long planId = Optional.ofNullable(plansByDomain.get(toDomain(gap.gapType())))
                    .map(RmPlanEntity::getPlanId)
                    .orElse(null);
            long resultId = insertResult(analysisId, companyPk, planId, gap, response.financials());
            for (DisclosureEvidence evidence : gap.evidence()) {
                extractReceiptNumber(evidence)
                        .ifPresent(rceptNo -> {
                            long evidenceId = upsertEvidence(companyPk, rceptNo, evidence);
                            linkEvidence(resultId, evidenceId);
                        });
            }
        }
    }

    private long insertAnalysis(
            long companyId,
            OffsetDateTime analysisAt,
            GapAnalysisResponse response,
            List<PlanFactEntity> confirmedFacts
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO gap_analysis (
                        company_id,
                        analysis_at,
                        retrieval_status,
                        crm_snapshot,
                        consultation_snapshot,
                        rule_version,
                        model_version,
                        scope_limited
                    )
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """, new String[]{"analysis_id"});
            ps.setLong(1, companyId);
            ps.setTimestamp(2, Timestamp.from(analysisAt.toInstant()));
            ps.setString(3, "SUCCESS");
            ps.setString(4, toJson(Map.of(
                    "company", response.company(),
                    "corpCode", response.corpCode()
            )));
            ps.setString(5, toJson(Map.of(
                    "used", !confirmedFacts.isEmpty(),
                    "confirmedPlanFacts", confirmedFacts.stream()
                            .map(this::factSnapshot)
                            .toList()
            )));
            ps.setString(6, "gap-rule-v1");
            ps.setString(7, null);
            ps.setBoolean(8, false);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertResult(
            long analysisId,
            long companyId,
            Long planId,
            GapResult gap,
            FinancialSnapshot financials
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO gap_result (
                        analysis_id,
                        company_id,
                        plan_id,
                        domain,
                        change_type,
                        assessment_status,
                        priority,
                        priority_reason,
                        changed_fields,
                        financial_signals,
                        environment_context,
                        summary
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                    """, new String[]{"result_id"});
            ps.setLong(1, analysisId);
            ps.setLong(2, companyId);
            if (planId == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, planId);
            }
            ps.setString(4, toDomain(gap.gapType()));
            ps.setString(5, toChangeType(gap.gapType()));
            ps.setString(6, "CONFIRMED");
            ps.setInt(7, priority(gap.gapType()));
            ps.setString(8, gap.reason());
            ps.setString(9, toJson(List.of(Map.of(
                    "field", toDomain(gap.gapType()),
                    "before", gap.existingInfo(),
                    "after", gap.latestInfo()
            ))));
            ps.setString(10, financials == null ? null : toJson(financials));
            ps.setString(11, toJson(Map.of(
                    "explanationSource", gap.explanationSource(),
                    "questionCount", gap.questions().size()
            )));
            ps.setString(12, gap.latestInfo());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long upsertEvidence(long companyId, String rceptNo, DisclosureEvidence evidence) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO external_evidence (
                    company_id,
                    source_system,
                    rcept_no,
                    report_name,
                    disclosed_at,
                    source_url,
                    evidence_text,
                    evidence_location,
                    retrieved_at
                )
                VALUES (?, 'OPENDART', ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (source_system, rcept_no)
                DO UPDATE SET
                    report_name = EXCLUDED.report_name,
                    disclosed_at = EXCLUDED.disclosed_at,
                    source_url = EXCLUDED.source_url,
                    evidence_text = EXCLUDED.evidence_text,
                    evidence_location = EXCLUDED.evidence_location,
                    retrieved_at = EXCLUDED.retrieved_at
                RETURNING evidence_id
                """,
                Long.class,
                companyId,
                rceptNo,
                evidence.title(),
                parseDate(evidence.date()),
                evidence.url(),
                evidence.title(),
                "OpenDART disclosure list");
    }

    private void linkEvidence(long resultId, long evidenceId) {
        jdbcTemplate.update("""
                INSERT INTO gap_result_evidence (result_id, evidence_id, usage_type, reason)
                VALUES (?, ?, 'PRIMARY', 'Matched by rule engine')
                ON CONFLICT (result_id, evidence_id) DO NOTHING
                """, resultId, evidenceId);
    }

    private Map<String, Object> factSnapshot(PlanFactEntity fact) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("factId", fact.getFactId());
        snapshot.put("planId", fact.getPlan() == null ? null : fact.getPlan().getPlanId());
        snapshot.put("qaId", fact.getQa() == null ? null : fact.getQa().getQaId());
        snapshot.put("domain", fact.getDomain());
        snapshot.put("scope", fact.getScope());
        snapshot.put("planStatus", fact.getPlanStatus());
        snapshot.put("factSource", fact.getFactSource());
        snapshot.put("extractionMethod", fact.getExtractionMethod());
        snapshot.put("verificationStatus", fact.getVerificationStatus());
        snapshot.put("effectiveFrom", fact.getEffectiveFrom());
        snapshot.put("confirmedAt", fact.getConfirmedAt());
        return snapshot;
    }

    private Optional<String> extractReceiptNumber(DisclosureEvidence evidence) {
        if (evidence.url() == null) {
            return Optional.empty();
        }
        Matcher matcher = RCEPT_NO.matcher(evidence.url());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.valueOf(LocalDate.parse(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String toDomain(GapType gapType) {
        return switch (gapType) {
            case INVESTMENT_PLAN_GAP -> "INVESTMENT";
            case FUNDING_PLAN_GAP -> "FUNDING";
            case FX_BUSINESS_GAP -> "FX";
        };
    }

    private String toChangeType(GapType gapType) {
        return switch (gapType) {
            case INVESTMENT_PLAN_GAP, FUNDING_PLAN_GAP, FX_BUSINESS_GAP -> "CONFLICT";
        };
    }

    private int priority(GapType gapType) {
        return switch (gapType) {
            case INVESTMENT_PLAN_GAP -> 1;
            case FUNDING_PLAN_GAP -> 2;
            case FX_BUSINESS_GAP -> 2;
        };
    }

    private long parseCompanyId(String companyId) {
        try {
            return Long.parseLong(companyId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid company id: " + companyId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unable to serialize analysis snapshot.", e);
        }
    }
}

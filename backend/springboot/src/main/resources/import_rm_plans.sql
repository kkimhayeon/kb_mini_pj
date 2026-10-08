BEGIN;

INSERT INTO company (corp_code, corp_name, consultation_date, rm_memo, is_active)
SELECT DISTINCT ON (corp_code) corp_code, company_name, consultation_date, rm_memo, TRUE
FROM rm_plan_import
ORDER BY corp_code, company_id
ON CONFLICT (corp_code) DO UPDATE
SET corp_name = EXCLUDED.corp_name,
    consultation_date = EXCLUDED.consultation_date,
    rm_memo = EXCLUDED.rm_memo,
    is_active = TRUE;

UPDATE company
SET is_active = FALSE
WHERE is_active = TRUE
  AND NOT EXISTS (
      SELECT 1
      FROM rm_plan_import
      WHERE rm_plan_import.corp_code = company.corp_code
  );

INSERT INTO rm_plan (
    company_id,
    source_plan_key,
    domain,
    scope,
    knowledge_status,
    plan_status,
    last_confirmed_at,
    amount,
    currency,
    expected_at,
    expected_at_label,
    expected_at_precision,
    source,
    evidence_level,
    source_reference,
    valid_until,
    review_due_at,
    validity_status,
    recorded_at,
    note
)
SELECT
    company.company_id,
    import.plan_id,
    CASE import.domain WHEN 'FOREIGN_BUSINESS' THEN 'FX' ELSE import.domain END,
    NULLIF(import.scope, ''),
    CASE import.knowledge_status
        WHEN 'EXPLICIT_NO' THEN 'EXPLICIT_NONE'
        WHEN 'UNKNOWN' THEN 'UNCONFIRMED'
        ELSE import.knowledge_status
    END,
    CASE import.plan_status
        WHEN 'UNDER_REVIEW' THEN 'REVIEW'
        WHEN 'CANCELLED' THEN 'CANCELED'
        ELSE import.plan_status
    END,
    import.last_confirmed_at,
    import.amount,
    NULLIF(import.currency, ''),
    NULL,
    NULLIF(import.expected_at, ''),
    NULLIF(import.expected_at_precision, ''),
    NULLIF(import.source, ''),
    CASE import.evidence_level
        WHEN 'DIRECT_CONFIRMATION' THEN 'DIRECT'
        ELSE import.evidence_level
    END,
    NULLIF(import.source_reference, ''),
    import.valid_until,
    import.review_due_at,
    CASE import.validity_status
        WHEN 'CHANGE_NOT_FOUND' THEN 'NO_CHANGE_EVIDENCE'
        WHEN 'RECONFIRM_REQUIRED' THEN 'RECHECK_NEEDED'
        WHEN 'JUDGMENT_PENDING' THEN 'PENDING'
        ELSE 'UNVERIFIED'
    END,
    COALESCE(import.recorded_at::TIMESTAMPTZ, CURRENT_TIMESTAMP),
    NULLIF(import.note, '')
FROM rm_plan_import import
JOIN company ON company.corp_code = import.corp_code
ON CONFLICT (company_id, source_plan_key) DO UPDATE
SET domain = EXCLUDED.domain,
    scope = EXCLUDED.scope,
    knowledge_status = EXCLUDED.knowledge_status,
    plan_status = EXCLUDED.plan_status,
    last_confirmed_at = EXCLUDED.last_confirmed_at,
    amount = EXCLUDED.amount,
    currency = EXCLUDED.currency,
    expected_at_label = EXCLUDED.expected_at_label,
    expected_at_precision = EXCLUDED.expected_at_precision,
    source = EXCLUDED.source,
    evidence_level = EXCLUDED.evidence_level,
    source_reference = EXCLUDED.source_reference,
    valid_until = EXCLUDED.valid_until,
    review_due_at = EXCLUDED.review_due_at,
    validity_status = EXCLUDED.validity_status,
    note = EXCLUDED.note;

COMMIT;

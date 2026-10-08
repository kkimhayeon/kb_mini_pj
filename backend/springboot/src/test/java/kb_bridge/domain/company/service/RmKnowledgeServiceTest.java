package kb_bridge.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RmKnowledgeServiceTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:rm_knowledge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS company (
                    company_id BIGINT PRIMARY KEY, corp_code VARCHAR(8), corp_name VARCHAR(200),
                    stock_code VARCHAR(20), is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    consultation_date DATE, rm_memo VARCHAR(500)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rm_plan (
                    plan_id BIGINT PRIMARY KEY, company_id BIGINT, source_plan_key VARCHAR(128),
                    domain VARCHAR(30), scope VARCHAR(500), knowledge_status VARCHAR(30),
                    plan_status VARCHAR(30), last_confirmed_at TIMESTAMP, amount NUMERIC(20,2),
                    currency VARCHAR(3), expected_at DATE, expected_at_label VARCHAR(128),
                    expected_at_precision VARCHAR(20), source VARCHAR(255), evidence_level VARCHAR(30),
                    source_reference VARCHAR(500), valid_until DATE, review_due_at DATE,
                    validity_status VARCHAR(30), recorded_at TIMESTAMP, note VARCHAR(500)
                )
                """);
        jdbcTemplate.update("DELETE FROM rm_plan");
        jdbcTemplate.update("DELETE FROM company");
    }

    @Test
    void readsNormalizedCompanyAndPlanRowsAndMapsDesignCodes() {
        jdbcTemplate.update("""
                INSERT INTO company (company_id, corp_code, corp_name, consultation_date, rm_memo)
                VALUES (42, '00126380', '삼성전자', DATE '2026-01-08', '가상 RM 회사 메모')
                """);
        jdbcTemplate.update("""
                INSERT INTO rm_plan (
                    plan_id, company_id, source_plan_key, domain, scope, knowledge_status,
                    plan_status, amount, currency, expected_at_label, expected_at_precision,
                    source, evidence_level, validity_status, note
                ) VALUES
                    (7, 42, 'investment-1', 'INVESTMENT', NULL, 'EXPLICIT_NONE',
                     'NOT_APPLICABLE', NULL, NULL, NULL, NULL, '가상 RM 상담',
                     'NONE', 'UNVERIFIED', '기존 정보 없음'),
                    (8, 42, 'funding-1', 'FUNDING', '회사채 발행 검토', 'EXPLICIT_YES',
                     'REVIEW', 1000000, 'KRW', '2026년 4분기', 'QUARTER',
                     '가상 RM 상담', 'DOCUMENT', 'UNVERIFIED', '자금조달 상담')
                """);

        RmKnowledgeService service = new RmKnowledgeService(jdbcTemplate);
        var company = service.findById("42");

        assertThat(service.findAll()).hasSize(1);
        assertThat(company.corpCode()).isEqualTo("00126380");
        assertThat(company.companyName()).isEqualTo("삼성전자");
        assertThat(company.plans()).hasSize(2);
        assertThat(company.plans()).filteredOn(plan -> "INVESTMENT".equals(plan.domain()))
                .singleElement()
                .satisfies(plan -> {
                    assertThat(plan.planId()).isEqualTo("7");
                    assertThat(plan.knowledgeStatus()).isEqualTo("EXPLICIT_NO");
                    assertThat(plan.lastConfirmedAt()).isNull();
                });
        assertThat(company.plans()).filteredOn(plan -> "FUNDING".equals(plan.domain()))
                .singleElement()
                .satisfies(plan -> {
                    assertThat(plan.planId()).isEqualTo("8");
                    assertThat(plan.planStatus()).isEqualTo("UNDER_REVIEW");
                    assertThat(plan.amount()).isEqualByComparingTo("1000000");
                    assertThat(plan.expectedAt()).isEqualTo("2026년 4분기");
                });
        assertThat(company.rmMemo()).isEqualTo("가상 RM 회사 메모");
    }

    @Test
    void excludesCompaniesNotPresentInTheCurrentListingFromTheActiveList() {
        jdbcTemplate.update("""
                INSERT INTO company (company_id, corp_code, corp_name, is_active)
                VALUES (42, '00126380', '현재 상장사', TRUE),
                       (43, '00126381', '비활성 회사', FALSE)
                """);

        var companies = new RmKnowledgeService(jdbcTemplate).findAll();

        assertThat(companies).extracting(company -> company.companyId()).containsExactly("42");
    }
}

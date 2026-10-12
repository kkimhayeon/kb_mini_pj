package kb_bridge.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import kb_bridge.domain.company.entity.Company;
import kb_bridge.domain.company.entity.ConsultationDtos.*;
import kb_bridge.domain.company.persistence.*;
import kb_bridge.rule.GapRuleEngine;

@SpringJUnitConfig(ConsultationServiceTest.Config.class)
class ConsultationServiceTest {
    @Autowired ConsultationService service;
    @Autowired CompanyRepository companies;
    @Autowired RmPlanRepository plans;
    @Autowired ConsultationRepository consultations;
    @Autowired ConsultationQaRepository questions;
    @Autowired PlanFactRepository facts;
    @Autowired PlatformTransactionManager transactions;

    private CompanyEntity company;
    private RmPlanEntity plan;
    private final OffsetDateTime originalDate = OffsetDateTime.parse("2025-01-01T00:00:00Z");
    private final OffsetDateTime answeredAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void prepare() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            facts.deleteAll();
            questions.deleteAll();
            consultations.deleteAll();
            plans.deleteAll();
            companies.deleteAll();
            company = companies.save(new CompanyEntity("00000001", "테스트기업", originalDate));
            plan = plans.save(new RmPlanEntity(company, "INVESTMENT", "A공장 증설 검토",
                    "EXPLICIT_YES", "REVIEW", originalDate, "TEST", "DIRECT", "UNVERIFIED", null));
        });
    }

    @Test
    void savesSameTextQuestionsWithTheirOwnAnswersAndMergesSeparateFields() {
        var response = service.createConsultation(company.getCompanyId(), request(
                question("같은 질문", "100억원입니다", fact("PLANNED", "RM_CONFIRMED", true,
                        new BigDecimal("10000000000"), null, null)),
                question("같은 질문", "내년 3월입니다", fact("UNKNOWN", "RM_CONFIRMED", true,
                        null, LocalDate.parse("2027-03-01"), null))));
        assertThat(response.questions()).extracting(QuestionAnswerResponse::answerText)
                .containsExactly("100억원입니다", "내년 3월입니다");
        assertThat(response.questions()).extracting(QuestionAnswerResponse::qaId).doesNotHaveDuplicates();
        assertThat(consultations.count()).isEqualTo(1);
        assertThat(facts.count()).isEqualTo(2);

        Company resolved = resolvedCompany();
        assertThat(resolved.investmentPlan()).contains("10000000000 KRW", "2027-03", "A공장", "진행 예정");
        assertThat(resolved.plans().getFirst().planStatus()).isEqualTo("PLANNED");
        // The old scope contains '검토', but the confirmed status is authoritative.
        var findings = new GapRuleEngine().detect(resolved, null, List.of(
                new kb_bridge.domain.company.entity.DisclosureEvidence("OpenDART", "신규 시설투자 결정", "2026-06-01", null)));
        assertThat(findings.getFirst().latestInfo()).doesNotContain("검토 단계");
    }

    @Test
    void invalidLaterAnswerRollsBackConsultationEarlierAnswersFactsAndCompanyDate() {
        assertThatThrownBy(() -> service.createConsultation(company.getCompanyId(), request(
                question("금액?", "100억원", fact("PLANNED", "RM_CONFIRMED", true,
                        new BigDecimal("10000000000"), null, null)),
                question("일정?", "", null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(consultations.count()).isZero();
        assertThat(questions.count()).isZero();
        assertThat(facts.count()).isZero();
        assertThat(companies.findById(company.getCompanyId()).orElseThrow().getLastConsultedAt().toInstant())
                .isEqualTo(originalDate.toInstant());
    }

    @Test
    void rawAnswerDoesNotAutomaticallyBecomeAConfirmedPlan() {
        service.createConsultation(company.getCompanyId(), request(question("계획?", "아직 확정된 것은 아닙니다", null)));
        assertThat(questions.count()).isEqualTo(1);
        assertThat(facts.count()).isZero();
    }

    @Test
    void pendingFactsAreStoredButDoNotOverrideAnalysisInputs() {
        service.createConsultation(company.getCompanyId(), request(question("금액?", "100억원",
                fact("PLANNED", "PENDING", false, new BigDecimal("10000000000"), null, null))));
        assertThat(facts.findAll().getFirst().getVerificationStatus()).isEqualTo("PENDING");
        assertThat(resolvedCompany().investmentPlan()).isEqualTo("A공장 증설 검토");
    }

    @Test
    void requiresExplicitConfirmationRatherThanJustAStatusString() {
        assertThatThrownBy(() -> service.createConsultation(company.getCompanyId(), request(
                question("금액?", "100억원", fact("PLANNED", "RM_CONFIRMED", false,
                        new BigDecimal("10000000000"), null, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicit RM confirmation");
        assertThat(consultations.count()).isZero();
    }

    @Test
    void rejectsWrongPlanDomainAndInvalidPrecisionOrAmount() {
        PlanFactRequest invalidDomain = new PlanFactRequest(plan.getPlanId(), "FX", "해외사업", "PLANNED",
                null, null, "RM_ENTERED", "PENDING", answeredAt, null, null, null, false);
        assertInvalid(invalidDomain, "domain");
        PlanFactRequest invalidPrecision = new PlanFactRequest(plan.getPlanId(), "INVESTMENT", null, "UNKNOWN",
                LocalDate.parse("2027-03-01"), "INVALID", "RM_ENTERED", "PENDING", answeredAt, null, null, null, false);
        assertInvalid(invalidPrecision, "expectedPrecision");
        assertInvalid(fact("PLANNED", "PENDING", false, new BigDecimal("-1"), null, null), "amount");
        assertInvalid(fact("PLANNED", "PENDING", false, new BigDecimal("0.001"), null, null), "amount");
    }

    @Test
    void ignoresFutureFactsAndKeepsFieldsFromEarlierConfirmedAnswers() {
        service.createConsultation(company.getCompanyId(), request(
                question("금액?", "100억원", fact("PLANNED", "RM_CONFIRMED", true, new BigDecimal("10000000000"), null, null))));
        service.createConsultation(company.getCompanyId(), request(
                question("일정?", "2027년 3월", fact("UNKNOWN", "RM_CONFIRMED", true, null, LocalDate.parse("2027-03-01"), null))));
        var future = new PlanFactRequest(plan.getPlanId(), "INVESTMENT", null, "CANCELED",
                null, null, "RM_ENTERED", "RM_CONFIRMED", OffsetDateTime.now().plusYears(1), null, null, null, true);
        service.createConsultation(company.getCompanyId(), request(question("상태?", "취소 예정", future)));
        assertThat(resolvedCompany().investmentPlan()).contains("10000000000", "2027-03", "진행 예정").doesNotContain("취소");
    }

    @Test
    void keepsDifferentPlansSeparateAndUsesNewestValueOnlyForTheSameField() {
        RmPlanEntity second = plans.save(new RmPlanEntity(company, "INVESTMENT", "B공장",
                "EXPLICIT_YES", "REVIEW", originalDate, "TEST", "DIRECT", "UNVERIFIED", null));
        service.createConsultation(company.getCompanyId(), request(
                question("금액?", "100", fact("PLANNED", "RM_CONFIRMED", true, new BigDecimal("100"), null, null))));
        service.createConsultation(company.getCompanyId(), request(
                question("금액 변경?", "200", fact("UNKNOWN", "RM_CONFIRMED", true, new BigDecimal("200"), null, null))));
        var secondFact = new PlanFactRequest(second.getPlanId(), "INVESTMENT", "B공장", "REVIEW",
                null, null, "RM_ENTERED", "RM_CONFIRMED", answeredAt, null, new BigDecimal("500"), "USD", true);
        var secondQuestion = new CreateQuestionRequest(second.getPlanId(), null, "RM_MANUAL", "B공장 금액?",
                new AnswerQuestionRequest("ANSWERED", "500달러", answeredAt, "고객", secondFact));
        service.createConsultation(company.getCompanyId(), request(secondQuestion));
        Company base = new Company("1", "00000001", "테스트기업", originalDate.toLocalDate(),
                "A공장 증설 검토", "없음", "없음", "", List.of(
                        new Company.PlanSummary(plan.getPlanId(), "INVESTMENT", "A공장"),
                        new Company.PlanSummary(second.getPlanId(), "INVESTMENT", "B공장")));
        Company result = ConfirmedPlanResolver.apply(base, confirmedFacts());
        assertThat(result.plans().getFirst().scope()).contains("200 KRW").doesNotContain("500", "100 KRW");
        assertThat(result.plans().get(1).scope()).contains("500 USD").doesNotContain("200");
    }

    @Test
    void canceledStatusIsNotReinterpretedAsAnActivePlanFromItsScope() {
        service.createConsultation(company.getCompanyId(), request(
                question("상태?", "취소했습니다", fact("CANCELED", "RM_CONFIRMED", true, null, null, "시설투자 진행 계획"))));
        var findings = new GapRuleEngine().detect(resolvedCompany(), null, List.of(
                new kb_bridge.domain.company.entity.DisclosureEvidence("OpenDART", "시설투자 결정", "2026-06-01", null)));
        assertThat(findings.getFirst().changeType()).isEqualTo(GapRuleEngine.ChangeType.NEEDS_CONFIRMATION);
        assertThat(findings.getFirst().latestInfo()).contains("완료 또는 취소");
    }

    @Test
    void acceptsFrontendDecimalStringsWithoutRoundingInTheRequestContract() {
        String json = """
                {"channel":"OTHER","questions":[{"planId":11,"questionText":"금액?",
                  "answer":{"answerText":"확인했습니다","planFact":{"planId":11,"domain":"INVESTMENT",
                    "amount":"999999999999999999.99","currency":"KRW","rmConfirmed":true,
                    "expectedAt":"2027-03-01","expectedPrecision":"MONTH"}}}]}
                """;
        var request = new tools.jackson.databind.ObjectMapper().readValue(json, CreateConsultationRequest.class);
        var fact = request.questions().getFirst().answer().planFact();
        assertThat(fact.amount()).isEqualByComparingTo("999999999999999999.99");
        assertThat(fact.expectedAt()).isEqualTo(LocalDate.parse("2027-03-01"));
        assertThat(fact.rmConfirmed()).isTrue();
    }
    private void assertInvalid(PlanFactRequest fact, String message) {
        assertThatThrownBy(() -> service.createConsultation(company.getCompanyId(), request(question("질문", "답변", fact))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        assertThat(consultations.count()).isZero();
    }

    private Company resolvedCompany() {
        Company base = new Company("1", "00000001", "테스트기업", originalDate.toLocalDate(),
                "A공장 증설 검토", "없음", "없음", "",
                List.of(new Company.PlanSummary(plan.getPlanId(), "INVESTMENT", "A공장 증설 검토")));
        return ConfirmedPlanResolver.apply(base, confirmedFacts());
    }

    private List<PlanFactEntity> confirmedFacts() {
        return facts.findByCompanyCompanyIdAndVerificationStatusOrderByFactIdDesc(company.getCompanyId(), "RM_CONFIRMED");
    }

    private CreateConsultationRequest request(CreateQuestionRequest... items) {
        return new CreateConsultationRequest(answeredAt, "OTHER", "RM", "테스트 상담", List.of(items));
    }

    private CreateQuestionRequest question(String text, String answer, PlanFactRequest fact) {
        return new CreateQuestionRequest(plan.getPlanId(), null, "RM_MANUAL", text,
                new AnswerQuestionRequest("ANSWERED", answer, answeredAt, "고객", fact));
    }

    private PlanFactRequest fact(String status, String verification, boolean confirmed,
                                 BigDecimal amount, LocalDate date, String scope) {
        return new PlanFactRequest(plan.getPlanId(), "INVESTMENT", scope, status, date, date == null ? null : "MONTH",
                "RM_ENTERED", verification, answeredAt, null, amount, amount == null ? null : "KRW", confirmed);
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CompanyRepository.class)
    @Import(ConsultationService.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:consultation_test;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("kb_bridge.domain.company.persistence");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}

package kb_bridge.domain.company.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import kb_bridge.domain.company.entity.ConsultationDtos.AnswerQuestionRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.ConsultationResponse;
import kb_bridge.domain.company.entity.ConsultationDtos.CreateConsultationRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.CreateQuestionRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.PlanFactRequest;
import kb_bridge.domain.company.entity.ConsultationDtos.QuestionAnswerResponse;
import kb_bridge.domain.company.persistence.CompanyEntity;
import kb_bridge.domain.company.persistence.CompanyRepository;
import kb_bridge.domain.company.persistence.ConsultationEntity;
import kb_bridge.domain.company.persistence.ConsultationQaEntity;
import kb_bridge.domain.company.persistence.ConsultationQaRepository;
import kb_bridge.domain.company.persistence.ConsultationRepository;
import kb_bridge.domain.company.persistence.PlanFactEntity;
import kb_bridge.domain.company.persistence.PlanFactRepository;
import kb_bridge.domain.company.persistence.RmPlanEntity;
import kb_bridge.domain.company.persistence.RmPlanRepository;

@Service
public class ConsultationService {

    private static final Set<String> CHANNELS = Set.of("VISIT", "PHONE", "EMAIL", "VIDEO", "OTHER");
    private static final Set<String> QUESTION_SOURCES = Set.of("AI_GENERATED", "RM_MANUAL");
    private static final Set<String> ANSWER_STATUSES = Set.of("UNANSWERED", "ANSWERED", "DECLINED", "UNKNOWN", "NEEDS_FOLLOWUP");
    private static final Set<String> DOMAINS = Set.of("INVESTMENT", "FUNDING", "FX");
    private static final Set<String> PLAN_STATUSES = Set.of("REVIEW", "PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELED", "NOT_APPLICABLE", "UNKNOWN");
    private static final Set<String> EXTRACTION_METHODS = Set.of("AI_EXTRACTED", "RM_ENTERED", "RULE_EXTRACTED");
    private static final Set<String> VERIFICATION_STATUSES = Set.of("PENDING", "RM_CONFIRMED", "REJECTED");

    private final CompanyRepository companyRepository;
    private final RmPlanRepository rmPlanRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationQaRepository consultationQaRepository;
    private final PlanFactRepository planFactRepository;

    public ConsultationService(
            CompanyRepository companyRepository,
            RmPlanRepository rmPlanRepository,
            ConsultationRepository consultationRepository,
            ConsultationQaRepository consultationQaRepository,
            PlanFactRepository planFactRepository
    ) {
        this.companyRepository = companyRepository;
        this.rmPlanRepository = rmPlanRepository;
        this.consultationRepository = consultationRepository;
        this.consultationQaRepository = consultationQaRepository;
        this.planFactRepository = planFactRepository;
    }

    @Transactional
    public ConsultationResponse createConsultation(Long companyId, CreateConsultationRequest request) {
        CompanyEntity company = findCompany(companyId);
        OffsetDateTime consultedAt = request.consultedAt() == null ? OffsetDateTime.now() : request.consultedAt();
        requireAllowed("channel", request.channel(), CHANNELS);

        ConsultationEntity consultation = consultationRepository.save(new ConsultationEntity(
                company,
                consultedAt,
                request.channel(),
                blankToNull(request.consultantName()),
                blankToNull(request.memo())
        ));
        company.updateLastConsultedAt(consultedAt);

        List<CreateQuestionRequest> questions = request.questions() == null ? List.of() : request.questions();
        for (CreateQuestionRequest question : questions) {
            consultationQaRepository.save(toQuestion(company, consultation, question));
        }
        return toResponse(consultation);
    }

    @Transactional(readOnly = true)
    public List<ConsultationResponse> findConsultations(Long companyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new IllegalArgumentException("Unknown company id: " + companyId);
        }
        return consultationRepository.findByCompanyCompanyIdOrderByConsultedAtDesc(companyId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public QuestionAnswerResponse answerQuestion(Long qaId, AnswerQuestionRequest request) {
        ConsultationQaEntity qa = consultationQaRepository.findById(qaId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown consultation QA id: " + qaId));
        String answerStatus = request.answerStatus() == null ? "ANSWERED" : request.answerStatus();
        requireAllowed("answerStatus", answerStatus, ANSWER_STATUSES);
        if ("ANSWERED".equals(answerStatus) && isBlank(request.answerText())) {
            throw new IllegalArgumentException("answerText is required when answerStatus is ANSWERED.");
        }
        OffsetDateTime answeredAt = "ANSWERED".equals(answerStatus)
                ? (request.answeredAt() == null ? OffsetDateTime.now() : request.answeredAt())
                : request.answeredAt();
        qa.answer(answerStatus, blankToNull(request.answerText()), answeredAt, blankToNull(request.answeredBy()));

        if (request.planFact() != null) {
            planFactRepository.save(toPlanFact(qa, request.planFact(), answeredAt));
        }
        return toResponse(qa);
    }

    private ConsultationQaEntity toQuestion(CompanyEntity company, ConsultationEntity consultation, CreateQuestionRequest request) {
        requireText("questionText", request.questionText());
        String questionSource = request.questionSource() == null ? "RM_MANUAL" : request.questionSource();
        requireAllowed("questionSource", questionSource, QUESTION_SOURCES);
        RmPlanEntity plan = request.planId() == null ? null : findPlanForCompany(request.planId(), company.getCompanyId());
        return new ConsultationQaEntity(
                consultation,
                company,
                plan,
                request.generatedFromResultId(),
                questionSource,
                request.questionText()
        );
    }

    private PlanFactEntity toPlanFact(ConsultationQaEntity qa, PlanFactRequest request, OffsetDateTime answeredAt) {
        RmPlanEntity plan = request.planId() == null
                ? qa.getPlan()
                : findPlanForCompany(request.planId(), qa.getCompany().getCompanyId());
        String domain = request.domain() != null
                ? request.domain()
                : (plan == null ? null : plan.getDomain());
        requireAllowed("domain", domain, DOMAINS);
        String planStatus = request.planStatus() == null ? "UNKNOWN" : request.planStatus();
        requireAllowed("planStatus", planStatus, PLAN_STATUSES);
        String extractionMethod = request.extractionMethod() == null ? "RM_ENTERED" : request.extractionMethod();
        requireAllowed("extractionMethod", extractionMethod, EXTRACTION_METHODS);
        String verificationStatus = request.verificationStatus() == null ? "PENDING" : request.verificationStatus();
        requireAllowed("verificationStatus", verificationStatus, VERIFICATION_STATUSES);
        OffsetDateTime confirmedAt = "RM_CONFIRMED".equals(verificationStatus) ? OffsetDateTime.now() : null;

        if ((request.expectedAt() == null) != (request.expectedPrecision() == null)) {
            throw new IllegalArgumentException("expectedAt and expectedPrecision must be provided together.");
        }

        return new PlanFactEntity(
                qa.getCompany(),
                plan,
                qa,
                domain,
                blankToNull(request.scope()),
                planStatus,
                request.expectedAt(),
                request.expectedPrecision(),
                "CONSULTATION_ANSWER",
                extractionMethod,
                verificationStatus,
                request.effectiveFrom() == null ? answeredAt : request.effectiveFrom(),
                confirmedAt,
                blankToNull(request.note())
        );
    }

    private ConsultationResponse toResponse(ConsultationEntity consultation) {
        List<QuestionAnswerResponse> questions = consultationQaRepository
                .findByConsultationConsultationIdOrderByQaId(consultation.getConsultationId())
                .stream()
                .map(this::toResponse)
                .toList();
        return new ConsultationResponse(
                consultation.getConsultationId(),
                consultation.getCompany().getCompanyId(),
                consultation.getConsultedAt(),
                consultation.getChannel(),
                consultation.getConsultantName(),
                consultation.getMemo(),
                questions
        );
    }

    private QuestionAnswerResponse toResponse(ConsultationQaEntity qa) {
        return new QuestionAnswerResponse(
                qa.getQaId(),
                qa.getConsultation().getConsultationId(),
                qa.getCompany().getCompanyId(),
                qa.getPlan() == null ? null : qa.getPlan().getPlanId(),
                qa.getGeneratedFromResultId(),
                qa.getQuestionSource(),
                qa.getQuestionText(),
                qa.getAnswerStatus(),
                qa.getAnswerText(),
                qa.getAnsweredAt(),
                qa.getAnsweredBy()
        );
    }

    private CompanyEntity findCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown company id: " + companyId));
    }

    private RmPlanEntity findPlanForCompany(Long planId, Long companyId) {
        RmPlanEntity plan = rmPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown RM plan id: " + planId));
        if (!plan.getCompany().getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("RM plan does not belong to company id: " + companyId);
        }
        return plan;
    }

    private void requireAllowed(String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed + ".");
        }
    }

    private void requireText(String field, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}

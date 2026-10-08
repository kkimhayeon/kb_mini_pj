package kb_bridge.domain.company.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "consultation_qa")
public class ConsultationQaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "qa_id")
    private Long qaId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false)
    private ConsultationEntity consultation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private RmPlanEntity plan;

    @Column(name = "generated_from_result_id")
    private Long generatedFromResultId;

    @Column(name = "question_source", nullable = false, length = 30)
    private String questionSource;

    @Column(name = "question_text", nullable = false)
    private String questionText;

    @Column(name = "answer_status", nullable = false, length = 30)
    private String answerStatus = "UNANSWERED";

    @Column(name = "answer_text")
    private String answerText;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    @Column(name = "answered_by", length = 100)
    private String answeredBy;

    protected ConsultationQaEntity() {
    }

    public ConsultationQaEntity(
            ConsultationEntity consultation,
            CompanyEntity company,
            RmPlanEntity plan,
            Long generatedFromResultId,
            String questionSource,
            String questionText
    ) {
        this.consultation = consultation;
        this.company = company;
        this.plan = plan;
        this.generatedFromResultId = generatedFromResultId;
        this.questionSource = questionSource;
        this.questionText = questionText;
    }

    public void answer(String answerStatus, String answerText, OffsetDateTime answeredAt, String answeredBy) {
        this.answerStatus = answerStatus;
        this.answerText = answerText;
        this.answeredAt = answeredAt;
        this.answeredBy = answeredBy;
    }

    public Long getQaId() {
        return qaId;
    }

    public ConsultationEntity getConsultation() {
        return consultation;
    }

    public CompanyEntity getCompany() {
        return company;
    }

    public RmPlanEntity getPlan() {
        return plan;
    }

    public Long getGeneratedFromResultId() {
        return generatedFromResultId;
    }

    public String getQuestionSource() {
        return questionSource;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getAnswerStatus() {
        return answerStatus;
    }

    public String getAnswerText() {
        return answerText;
    }

    public OffsetDateTime getAnsweredAt() {
        return answeredAt;
    }

    public String getAnsweredBy() {
        return answeredBy;
    }
}

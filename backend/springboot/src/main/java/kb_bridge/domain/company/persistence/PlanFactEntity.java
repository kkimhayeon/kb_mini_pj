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

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "plan_fact")
public class PlanFactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fact_id")
    private Long factId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private RmPlanEntity plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "qa_id")
    private ConsultationQaEntity qa;

    @Column(name = "domain", nullable = false, length = 30)
    private String domain;

    @Column(name = "scope")
    private String scope;

    @Column(name = "plan_status", nullable = false, length = 30)
    private String planStatus;

    @Column(name = "expected_at")
    private LocalDate expectedAt;

    @Column(name = "expected_precision", length = 20)
    private String expectedPrecision;

    @Column(name = "fact_source", nullable = false, length = 30)
    private String factSource;

    @Column(name = "extraction_method", nullable = false, length = 30)
    private String extractionMethod;

    @Column(name = "verification_status", nullable = false, length = 30)
    private String verificationStatus;

    @Column(name = "effective_from")
    private OffsetDateTime effectiveFrom;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "note")
    private String note;

    protected PlanFactEntity() {
    }

    public PlanFactEntity(
            CompanyEntity company,
            RmPlanEntity plan,
            ConsultationQaEntity qa,
            String domain,
            String scope,
            String planStatus,
            LocalDate expectedAt,
            String expectedPrecision,
            String factSource,
            String extractionMethod,
            String verificationStatus,
            OffsetDateTime effectiveFrom,
            OffsetDateTime confirmedAt,
            String note
    ) {
        this.company = company;
        this.plan = plan;
        this.qa = qa;
        this.domain = domain;
        this.scope = scope;
        this.planStatus = planStatus;
        this.expectedAt = expectedAt;
        this.expectedPrecision = expectedPrecision;
        this.factSource = factSource;
        this.extractionMethod = extractionMethod;
        this.verificationStatus = verificationStatus;
        this.effectiveFrom = effectiveFrom;
        this.confirmedAt = confirmedAt;
        this.note = note;
    }

    public Long getFactId() {
        return factId;
    }

    public RmPlanEntity getPlan() {
        return plan;
    }

    public ConsultationQaEntity getQa() {
        return qa;
    }

    public String getDomain() {
        return domain;
    }

    public String getScope() {
        return scope;
    }

    public String getPlanStatus() {
        return planStatus;
    }

    public LocalDate getExpectedAt() {
        return expectedAt;
    }

    public String getExpectedPrecision() {
        return expectedPrecision;
    }

    public String getFactSource() {
        return factSource;
    }

    public String getExtractionMethod() {
        return extractionMethod;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public OffsetDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public String getNote() {
        return note;
    }
}

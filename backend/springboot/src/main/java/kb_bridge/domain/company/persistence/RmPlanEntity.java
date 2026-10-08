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
@Table(name = "rm_plan")
public class RmPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long planId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Column(name = "domain", nullable = false, length = 30)
    private String domain;

    @Column(name = "scope")
    private String scope;

    @Column(name = "knowledge_status", nullable = false, length = 30)
    private String knowledgeStatus;

    @Column(name = "plan_status", nullable = false, length = 30)
    private String planStatus;

    @Column(name = "last_confirmed_at")
    private OffsetDateTime lastConfirmedAt;

    @Column(name = "source")
    private String source;

    @Column(name = "evidence_level", length = 30)
    private String evidenceLevel;

    @Column(name = "validity_status", length = 30)
    private String validityStatus;

    @Column(name = "note")
    private String note;

    protected RmPlanEntity() {
    }

    public RmPlanEntity(
            CompanyEntity company,
            String domain,
            String scope,
            String knowledgeStatus,
            String planStatus,
            OffsetDateTime lastConfirmedAt,
            String source,
            String evidenceLevel,
            String validityStatus,
            String note
    ) {
        this.company = company;
        this.domain = domain;
        this.scope = scope;
        this.knowledgeStatus = knowledgeStatus;
        this.planStatus = planStatus;
        this.lastConfirmedAt = lastConfirmedAt;
        this.source = source;
        this.evidenceLevel = evidenceLevel;
        this.validityStatus = validityStatus;
        this.note = note;
    }

    public CompanyEntity getCompany() {
        return company;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getDomain() {
        return domain;
    }

    public String getScope() {
        return scope;
    }

    public String getNote() {
        return note;
    }
}

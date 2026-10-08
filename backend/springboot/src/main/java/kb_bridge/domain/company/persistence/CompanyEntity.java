package kb_bridge.domain.company.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "company")
public class CompanyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "corp_code", nullable = false, length = 8)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 200)
    private String corpName;

    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(name = "last_consulted_at")
    private OffsetDateTime lastConsultedAt;

    protected CompanyEntity() {
    }

    public CompanyEntity(String corpCode, String corpName, OffsetDateTime lastConsultedAt) {
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.lastConsultedAt = lastConsultedAt;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getCorpCode() {
        return corpCode;
    }

    public String getCorpName() {
        return corpName;
    }

    public OffsetDateTime getLastConsultedAt() {
        return lastConsultedAt;
    }

    public void updateLastConsultedAt(OffsetDateTime lastConsultedAt) {
        if (this.lastConsultedAt == null || this.lastConsultedAt.isBefore(lastConsultedAt)) {
            this.lastConsultedAt = lastConsultedAt;
        }
    }
}

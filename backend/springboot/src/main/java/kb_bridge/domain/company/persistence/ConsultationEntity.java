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
@Table(name = "consultation")
public class ConsultationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consultation_id")
    private Long consultationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyEntity company;

    @Column(name = "consulted_at", nullable = false)
    private OffsetDateTime consultedAt;

    @Column(name = "channel", nullable = false, length = 30)
    private String channel;

    @Column(name = "consultant_name", length = 100)
    private String consultantName;

    @Column(name = "memo")
    private String memo;

    protected ConsultationEntity() {
    }

    public ConsultationEntity(CompanyEntity company, OffsetDateTime consultedAt, String channel, String consultantName, String memo) {
        this.company = company;
        this.consultedAt = consultedAt;
        this.channel = channel;
        this.consultantName = consultantName;
        this.memo = memo;
    }

    public Long getConsultationId() {
        return consultationId;
    }

    public CompanyEntity getCompany() {
        return company;
    }

    public OffsetDateTime getConsultedAt() {
        return consultedAt;
    }

    public String getChannel() {
        return channel;
    }

    public String getConsultantName() {
        return consultantName;
    }

    public String getMemo() {
        return memo;
    }
}

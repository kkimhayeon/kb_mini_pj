package kb_bridge.domain.company.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsultationRepository extends JpaRepository<ConsultationEntity, Long> {

    List<ConsultationEntity> findByCompanyCompanyIdOrderByConsultedAtDesc(Long companyId);
}

package kb_bridge.domain.company.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsultationQaRepository extends JpaRepository<ConsultationQaEntity, Long> {

    List<ConsultationQaEntity> findByConsultationConsultationIdOrderByQaId(Long consultationId);
}

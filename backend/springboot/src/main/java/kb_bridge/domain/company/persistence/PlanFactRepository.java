package kb_bridge.domain.company.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanFactRepository extends JpaRepository<PlanFactEntity, Long> {

    List<PlanFactEntity> findByCompanyCompanyIdAndVerificationStatusOrderByFactIdDesc(
            Long companyId,
            String verificationStatus
    );
}

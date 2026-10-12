package kb_bridge.domain.company.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;

public interface PlanFactRepository extends JpaRepository<PlanFactEntity, Long> {

    @EntityGraph(attributePaths = "plan")
    List<PlanFactEntity> findByCompanyCompanyIdAndVerificationStatusOrderByFactIdDesc(
            Long companyId,
            String verificationStatus
    );
}

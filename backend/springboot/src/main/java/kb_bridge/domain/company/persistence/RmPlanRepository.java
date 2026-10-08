package kb_bridge.domain.company.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RmPlanRepository extends JpaRepository<RmPlanEntity, Long> {

    List<RmPlanEntity> findByCompanyCompanyId(Long companyId);
}

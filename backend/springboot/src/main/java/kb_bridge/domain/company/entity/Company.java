package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.util.List;

public record Company(
        String companyId,
        String corpCode,
        String companyName,
        LocalDate consultationDate,
        List<PlanKnowledge> plans,
        String rmMemo
) {
}

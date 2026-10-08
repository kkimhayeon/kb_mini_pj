package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ConsultationRecord(
        Long consultationRecordId,
        String companyId,
        LocalDate consultationDate,
        String consultationText,
        LocalDateTime createdAt
) {
}

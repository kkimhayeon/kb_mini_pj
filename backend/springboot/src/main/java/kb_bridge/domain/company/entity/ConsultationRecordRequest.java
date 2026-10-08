package kb_bridge.domain.company.entity;

import java.time.LocalDate;

public record ConsultationRecordRequest(
        LocalDate consultationDate,
        String consultationText
) {
}

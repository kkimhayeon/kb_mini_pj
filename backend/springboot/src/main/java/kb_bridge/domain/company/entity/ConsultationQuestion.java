package kb_bridge.domain.company.entity;

import java.time.LocalDateTime;

public record ConsultationQuestion(
        Long questionId,
        String text,
        String answer,
        LocalDateTime answeredAt,
        String planId
) {
}

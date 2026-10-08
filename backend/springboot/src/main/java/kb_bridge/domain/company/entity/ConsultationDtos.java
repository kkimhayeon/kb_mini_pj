package kb_bridge.domain.company.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConsultationDtos {

    private ConsultationDtos() {
    }

    public record CreateConsultationRequest(
            OffsetDateTime consultedAt,
            String channel,
            String consultantName,
            String memo,
            List<CreateQuestionRequest> questions
    ) {
    }

    public record CreateQuestionRequest(
            Long planId,
            Long generatedFromResultId,
            String questionSource,
            String questionText
    ) {
    }

    public record AnswerQuestionRequest(
            String answerStatus,
            String answerText,
            OffsetDateTime answeredAt,
            String answeredBy,
            PlanFactRequest planFact
    ) {
    }

    public record PlanFactRequest(
            Long planId,
            String domain,
            String scope,
            String planStatus,
            LocalDate expectedAt,
            String expectedPrecision,
            String extractionMethod,
            String verificationStatus,
            OffsetDateTime effectiveFrom,
            String note
    ) {
    }

    public record ConsultationResponse(
            Long consultationId,
            Long companyId,
            OffsetDateTime consultedAt,
            String channel,
            String consultantName,
            String memo,
            List<QuestionAnswerResponse> questions
    ) {
    }

    public record QuestionAnswerResponse(
            Long qaId,
            Long consultationId,
            Long companyId,
            Long planId,
            Long generatedFromResultId,
            String questionSource,
            String questionText,
            String answerStatus,
            String answerText,
            OffsetDateTime answeredAt,
            String answeredBy
    ) {
    }
}

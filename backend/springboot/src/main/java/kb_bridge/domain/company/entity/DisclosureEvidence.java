package kb_bridge.domain.company.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record DisclosureEvidence(
        String source,
        String title,
        String date,
        String url,
        String receiptNumber,
        String correctionStatus,
        @JsonIgnore String content,
        String excerpt
) {
}

package kb_bridge.domain.company.entity;

import java.util.List;

public record DisclosureBatch(
        List<DisclosureEvidence> disclosures,
        List<String> collectionMessages
) {
}

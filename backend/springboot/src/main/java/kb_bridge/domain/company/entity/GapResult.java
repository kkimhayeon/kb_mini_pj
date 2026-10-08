package kb_bridge.domain.company.entity;

import java.util.List;

import kb_bridge.rule.GapRuleEngine.GapType;

public record GapResult(
        GapType gapType,
        String existingInfo,
        String latestInfo,
        String reason,
        String explanationSource,
        List<DisclosureEvidence> evidence,
        List<String> questions,
        String domain,
        String planId,
        String changeType,
        String priority
) {
}

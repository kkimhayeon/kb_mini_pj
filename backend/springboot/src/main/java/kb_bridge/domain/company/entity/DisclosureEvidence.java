package kb_bridge.domain.company.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DisclosureEvidence(String source, String title, String date, String url, Details details) {
    public DisclosureEvidence(String source, String title, String date, String url) {
        this(source, title, date, url, null);
    }
    public record Details(String domain, String projectName, BigDecimal amount, String currency,
                          LocalDate expectedAt, String expectedPrecision, String receiptNumber, String sourceField) { }
}

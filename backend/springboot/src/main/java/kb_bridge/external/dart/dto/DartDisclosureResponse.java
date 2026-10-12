package kb_bridge.external.dart.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DartDisclosureResponse(
        String status,
        String message,
        List<Item> list,
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("total_page") Integer totalPage
) {
    public record Item(
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("report_nm") String reportName,
            @JsonProperty("rcept_no") String receiptNumber,
            @JsonProperty("rcept_dt") String receiptDate
    ) {
    }
}

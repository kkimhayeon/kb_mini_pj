package kb_bridge.external.dart.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DartDisclosureResponse(
        String status,
        String message,
        @JsonProperty("total_page") Integer totalPage,
        List<Item> list
) {
    public record Item(
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("report_nm") String reportName,
            @JsonProperty("rcept_no") String receiptNumber,
            @JsonProperty("rcept_dt") String receiptDate,
            @JsonProperty("rm") String remark
    ) {
    }
}

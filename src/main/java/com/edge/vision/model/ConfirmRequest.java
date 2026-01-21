package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ConfirmRequest {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("confirmed_part_name")
    private String confirmedPartName;

    @JsonProperty("batch_id")
    private String batchId;

    private String operator;
}
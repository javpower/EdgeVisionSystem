package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ConfirmRequest {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("confirmed_part_name")
    private String confirmedPartName;

    @JsonProperty("batch_id")
    private String batchId;

    private String operator;

    @JsonProperty("workpiece_bbox")
    private List<Double> workpieceBbox;  // 工件整体边界框 [x1, y1, x2, y2]

    @JsonProperty("workpiece_corners")
    private List<List<Double>> workpieceCorners;  // 工件四角 [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
}
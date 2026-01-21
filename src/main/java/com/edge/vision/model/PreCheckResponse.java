package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreCheckResponse {
    private String requestId;

    @JsonProperty("suggested_type")
    private String suggestedType; // 类型识别模型的建议（可为null）

    @JsonProperty("preview_image")
    private String previewImage;  // base64编码的预览图

    @JsonProperty("camera_count")
    private int cameraCount;

    @JsonProperty("image_shape")
    private int[] imageShape;     // [height, width, channels]

    @JsonProperty("workpiece_bbox")
    private List<Double> workpieceBbox;  // 工件整体边界框 [x1, y1, x2, y2]

    @JsonProperty("workpiece_corners")
    private List<List<Double>> workpieceCorners;  // 工件四角 [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
}
package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
package com.edge.vision.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class InspectionEntity {
    private String id;
    private String deviceId;
    private String batchId;
    private String partName;
    private String operator;
    private LocalDateTime timestamp;
    private String imagePath;

    // 质检结果
    private Boolean passed;
    private String qualityStatus; // PASS/FAIL
    private String qualityMessage;

    // 使用的模板
    private String templateId;

    // 元数据（包含检测详情、模板比对结果等）
    private Map<String, Object> meta;

    private boolean uploaded = false;
}

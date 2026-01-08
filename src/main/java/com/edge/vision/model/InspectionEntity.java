package com.edge.vision.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
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
    private Map<String, Object> meta; // 存储分析结果
    private boolean uploaded = false;
}
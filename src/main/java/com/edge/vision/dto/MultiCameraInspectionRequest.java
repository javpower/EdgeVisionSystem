package com.edge.vision.dto;

/**
 * 多摄像头质检请求
 */
public class MultiCameraInspectionRequest {
    private String partType;
    private String batchId;
    private String operator;

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}

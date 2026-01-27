package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 数据采集请求
 */
public class CollectDataRequest {
    @JsonProperty("confirmed_part_name")
    private String confirmedPartName;

    @JsonProperty("batch_id")
    private String batchId;

    private String operator;

    @JsonProperty("save_dir")
    private String saveDir;  // 保存目录，例如：data/collected/EKS

    public String getConfirmedPartName() {
        return confirmedPartName;
    }

    public void setConfirmedPartName(String confirmedPartName) {
        this.confirmedPartName = confirmedPartName;
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

    public String getSaveDir() {
        return saveDir;
    }

    public void setSaveDir(String saveDir) {
        this.saveDir = saveDir;
    }
}

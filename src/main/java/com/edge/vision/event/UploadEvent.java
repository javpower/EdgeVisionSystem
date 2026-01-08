package com.edge.vision.event;

import com.edge.vision.model.InspectionEntity;
import org.springframework.context.ApplicationEvent;

public class UploadEvent extends ApplicationEvent {
    private final InspectionEntity entity;
    private final String imagePath;

    public UploadEvent(Object source, InspectionEntity entity, String imagePath) {
        super(source);
        this.entity = entity;
        this.imagePath = imagePath;
    }

    public InspectionEntity getEntity() {
        return entity;
    }

    public String getImagePath() {
        return imagePath;
    }
}
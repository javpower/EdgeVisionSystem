package com.edge.vision.core.camera;

import org.opencv.core.Mat;

public interface CameraSource {
    /**
     * 打开相机源
     * @return 是否成功打开
     */
    boolean open();
    
    /**
     * 读取一帧图像
     * @return 图像帧，如果读取失败返回null
     */
    Mat read();
    
    /**
     * 关闭相机源
     */
    void close();
    
    /**
     * 检查相机是否已打开
     * @return 是否已打开
     */
    boolean isOpened();
}
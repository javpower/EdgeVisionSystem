package com.edge.vision.core.quality;

/**
 * 匹配策略枚举
 */
public enum MatchStrategy {
    /**
     * 拓扑图匹配
     * 优点：支持旋转、平移、尺度变化，适合复杂场景
     * 缺点：全局最优匹配，统计可能不准确
     */
    TOPOLOGY,

    /**
     * 坐标直接匹配
     * 优点：一对一关系明确，错检漏检一目了然
     * 缺点：对旋转、平移敏感，需要预先校正坐标
     */
    COORDINATE,

    /**
     * 裁剪区域匹配（Crop Area Matching）
     * 优点：建模图和识别图在同一坐标系，直接比对即可，精度高
     * 缺点：需要先检测工件位置并裁剪
     * 适用场景：工件位置可能变化，但可以通过模板匹配定位
     */
    CROP_AREA,

    /**
     * 射影几何指纹匹配（Cross-Ratio Fingerprint Matching）
     * 优点：基于交比不变性，完全支持射影变换（平移、旋转、缩放、透视），计算量O(N)
     * 缺点：需要画布四角可见，适用于刚体变换
     * 适用场景：存在透视变换的场景，如倾斜视角拍摄
     */
    CROSS_RATIO
}

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
    COORDINATE
}

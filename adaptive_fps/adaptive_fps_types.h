/**
 * @file adaptive_fps_types.h
 * @brief 自适应帧率控制 - 数据结构定义
 * @version 2.0
 * @date 2025-01-31
 */

#pragma once

#include <cstdint>
#include <string>

namespace adaptive {

// ==================== 帧率四档 ====================
constexpr int FPS_LEVELS[] = {15, 30, 45, 60};
constexpr int FPS_LEVEL_COUNT = 4;
constexpr int FPS_MIN = 15;
constexpr int FPS_MAX = 60;

// ==================== 紧急度 ====================
enum class Urgency {
    Critical,   // 紧急：立即执行，≤50ms（队列即将见底）
    High,       // 高优先级：立即执行，≤200ms（预测式预警）
    Normal,     // 正常：可短暂过渡（常规降帧）
    Low         // 低优先级：平滑过渡（升帧）
};

// ==================== 触发原因 ====================
enum class Reason {
    PredictWarning,    // 预测预警（趋势分析触发）
    PredictCritical,   // 预测紧急（趋势急剧恶化）
    ArrivalLow,        // 到达率不足 (<85%)
    JitterHigh,        // 抖动过高 (>30%)
    QueueLow,          // 队列不足 (<3帧)
    RttHigh,           // RTT过高 (>300ms)
    LossHigh,          // 丢包过高 (>5%)
    NetworkRecover     // 网络恢复，可升帧
};

// ==================== 网络指标 ====================
struct NetworkMetrics {
    double arrivalRate = 1.0;      // 到达率 (0.0 ~ 1.0)
    double jitterRatio = 0.0;      // 抖动比例 (jitterEma / expectedInterval)
    double jitterEma = 0.0;        // 抖动 EMA (ms)
    int    arrivalFps = 60;        // 实际到达帧率
    int    queueDepth = 9;         // 队列帧数
    int    rttMs = 0;              // RTT 毫秒
    double lossRate = 0.0;         // 丢包率 (0.0 ~ 1.0)
    int64_t timestamp = 0;         // 时间戳 (ms)
};

// ==================== 趋势指标 ====================
struct TrendMetrics {
    double jitterTrend = 0.0;      // 抖动变化率 (ms/s)，正值表示上升
    double arrivalSlope = 0.0;     // 到达率斜率 (%/s)，负值表示下降
    int    queueDropSpeed = 0;     // 队列下降速度 (帧/s)，正值表示下降
    double rttTrend = 0.0;         // RTT 变化率 (ms/s)
    double riskScore = 0.0;        // 综合风险分 (0~1)
    bool   isPredictDrop = false;  // 是否预测需要降帧
};

// ==================== set_fps 指令 ====================
struct SetFpsCommand {
    int     fps = 60;              // 目标帧率
    Urgency urgency = Urgency::Normal;
    Reason  reason = Reason::ArrivalLow;
    int     bitrate = 0;           // 建议码率 (bps)，0 表示不指定
    int64_t timestamp = 0;
};

// ==================== 阈值配置 ====================
struct AdaptiveConfig {
    // 降帧阈值
    double arrivalRateDropThreshold  = 0.85;   // 到达率 < 85% 触发降帧
    double jitterRatioDropThreshold  = 0.30;   // 抖动比例 > 30% 触发降帧
    int    queueDepthDropThreshold   = 3;      // 队列 < 3帧 触发降帧
    int    rttDropThresholdMs        = 300;    // RTT > 300ms 触发降帧
    double lossRateDropThreshold     = 0.05;   // 丢包 > 5% 触发降帧
    
    // 趋势预测阈值
    double jitterTrendThreshold      = 2.0;    // 抖动变化率 > 2ms/s 预警
    double arrivalSlopeThreshold     = -0.05;  // 到达率斜率 < -5%/s 预警
    int    queueDropSpeedThreshold   = 3;      // 队列下降 >= 3帧/s 预警
    double rttTrendThreshold         = 50.0;   // RTT变化率 > 50ms/s 预警
    double riskScoreThreshold        = 0.5;    // 风险分 > 0.5 触发预警
    
    // 升帧阈值
    double arrivalRateUpThreshold    = 0.90;   // 到达率 >= 90% 允许升帧
    double jitterRatioUpThreshold    = 0.20;   // 抖动比例 < 20% 允许升帧
    int    queueDepthUpThreshold     = 5;      // 队列 >= 5帧 允许升帧
    int    stableTimeMs              = 1000;   // 稳定 >= 1秒 才升帧
    
    // 升帧幅度
    int    upgradeStep               = 5;      // 每次升 5fps
    
    // EMA 平滑系数
    double jitterEmaAlpha            = 0.3;    // 抖动 EMA 系数
};

// ==================== 工具函数 ====================

inline const char* urgencyToString(Urgency u) {
    switch (u) {
        case Urgency::Critical: return "critical";
        case Urgency::High:     return "high";
        case Urgency::Normal:   return "normal";
        case Urgency::Low:      return "low";
        default:                return "normal";
    }
}

inline const char* reasonToString(Reason r) {
    switch (r) {
        case Reason::PredictWarning:  return "predict_warning";
        case Reason::PredictCritical: return "predict_critical";
        case Reason::ArrivalLow:      return "arrival_low";
        case Reason::JitterHigh:      return "jitter_high";
        case Reason::QueueLow:        return "queue_low";
        case Reason::RttHigh:         return "rtt_high";
        case Reason::LossHigh:        return "loss_high";
        case Reason::NetworkRecover:  return "network_recover";
        default:                      return "unknown";
    }
}

// 获取四档中最接近的帧率
inline int nearestFpsLevel(int fps) {
    int nearest = FPS_LEVELS[0];
    int minDiff = abs(fps - nearest);
    
    for (int i = 1; i < FPS_LEVEL_COUNT; i++) {
        int diff = abs(fps - FPS_LEVELS[i]);
        if (diff < minDiff) {
            minDiff = diff;
            nearest = FPS_LEVELS[i];
        }
    }
    return nearest;
}

// 获取下一个更低的档位
inline int getLowerFpsLevel(int currentFps) {
    for (int i = FPS_LEVEL_COUNT - 1; i >= 0; i--) {
        if (FPS_LEVELS[i] < currentFps) {
            return FPS_LEVELS[i];
        }
    }
    return FPS_LEVELS[0];  // 最低 15fps
}

// 获取下一个更高的档位
inline int getHigherFpsLevel(int currentFps) {
    for (int i = 0; i < FPS_LEVEL_COUNT; i++) {
        if (FPS_LEVELS[i] > currentFps) {
            return FPS_LEVELS[i];
        }
    }
    return FPS_LEVELS[FPS_LEVEL_COUNT - 1];  // 最高 60fps
}

// 根据帧率计算建议码率
inline int calculateBitrate(int fps) {
    if (fps >= 60) return 10000000;  // 10 Mbps
    if (fps >= 45) return 7000000;   // 7 Mbps
    if (fps >= 30) return 5000000;   // 5 Mbps
    return 2500000;                   // 2.5 Mbps
}

} // namespace adaptive

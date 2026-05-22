/**
 * @file adaptive_fps_decider.cpp
 * @brief 自适应帧率决策器实现
 */

#include "adaptive_fps_decider.h"
#include <algorithm>
#include <cmath>

namespace adaptive {

AdaptiveFpsDecider::AdaptiveFpsDecider(const AdaptiveConfig& config)
    : m_config(config)
{
}

std::optional<SetFpsCommand> AdaptiveFpsDecider::decide(
    const NetworkMetrics& metrics,
    const TrendMetrics& trend,
    int currentFps,
    int originFps)
{
    // 优先检查降帧条件
    auto dropCmd = decideDropFps(metrics, trend, currentFps);
    if (dropCmd) {
        m_isStable = false;  // 重置稳定状态
        m_lastFps = dropCmd->fps;
        return dropCmd;
    }
    
    // 检查升帧条件
    auto upCmd = decideUpgradeFps(metrics, trend, currentFps, originFps);
    if (upCmd) {
        m_lastFps = upCmd->fps;
        return upCmd;
    }
    
    return std::nullopt;
}

std::optional<SetFpsCommand> AdaptiveFpsDecider::decideDropFps(
    const NetworkMetrics& metrics, 
    const TrendMetrics& trend,
    int currentFps)
{
    int targetFps = currentFps;
    Urgency urgency = Urgency::Normal;
    Reason reason = Reason::ArrivalLow;
    bool shouldDrop = false;
    
    // ========================================
    // 条件0：队列即将见底（最高优先级）
    // ========================================
    if (metrics.queueDepth <= 1) {
        targetFps = FPS_MIN;  // 直接降到最低 15fps
        urgency = Urgency::Critical;
        reason = Reason::QueueLow;
        shouldDrop = true;
    }
    // ========================================
    // 条件1：预测式降帧（趋势分析）⭐核心
    // ========================================
    else if (trend.isPredictDrop) {
        if (trend.riskScore > 0.7) {
            // 风险很高，降两档
            targetFps = getLowerFpsLevel(getLowerFpsLevel(currentFps));
            urgency = Urgency::High;
            reason = Reason::PredictCritical;
        } else {
            // 风险中等，降一档
            targetFps = getLowerFpsLevel(currentFps);
            urgency = Urgency::High;
            reason = Reason::PredictWarning;
        }
        shouldDrop = true;
    }
    // ========================================
    // 条件2：到达率不足
    // ========================================
    else if (metrics.arrivalRate < m_config.arrivalRateDropThreshold) {
        // 降到实际到达帧率
        targetFps = std::max(FPS_MIN, metrics.arrivalFps);
        targetFps = nearestFpsLevel(targetFps);
        urgency = Urgency::Normal;
        reason = Reason::ArrivalLow;
        shouldDrop = true;
    }
    // ========================================
    // 条件3：抖动过高
    // ========================================
    else if (metrics.jitterRatio > m_config.jitterRatioDropThreshold) {
        targetFps = getLowerFpsLevel(currentFps);
        urgency = Urgency::Normal;
        reason = Reason::JitterHigh;
        shouldDrop = true;
    }
    // ========================================
    // 条件4：队列不足
    // ========================================
    else if (metrics.queueDepth < m_config.queueDepthDropThreshold) {
        targetFps = getLowerFpsLevel(currentFps);
        urgency = Urgency::Normal;
        reason = Reason::QueueLow;
        shouldDrop = true;
    }
    // ========================================
    // 条件5：RTT过高
    // ========================================
    else if (metrics.rttMs > m_config.rttDropThresholdMs) {
        targetFps = getLowerFpsLevel(currentFps);
        urgency = Urgency::Normal;
        reason = Reason::RttHigh;
        shouldDrop = true;
    }
    // ========================================
    // 条件6：丢包过高
    // ========================================
    else if (metrics.lossRate > m_config.lossRateDropThreshold) {
        targetFps = getLowerFpsLevel(currentFps);
        urgency = Urgency::Normal;
        reason = Reason::LossHigh;
        shouldDrop = true;
    }
    
    // 只有当目标帧率低于当前帧率，且与上次不同时才发送
    if (shouldDrop && targetFps < currentFps && targetFps != m_lastFps) {
        SetFpsCommand cmd;
        cmd.fps = targetFps;
        cmd.urgency = urgency;
        cmd.reason = reason;
        cmd.bitrate = calculateBitrate(targetFps);
        cmd.timestamp = metrics.timestamp;
        return cmd;
    }
    
    return std::nullopt;
}

std::optional<SetFpsCommand> AdaptiveFpsDecider::decideUpgradeFps(
    const NetworkMetrics& metrics, 
    const TrendMetrics& trend,
    int currentFps, 
    int originFps)
{
    // 已经是最高帧率
    if (currentFps >= originFps) {
        return std::nullopt;
    }
    
    // ===== 升帧条件（全部满足）=====
    bool canUpgrade = 
        (metrics.jitterRatio < m_config.jitterRatioUpThreshold) &&   // 抖动稳定
        (trend.jitterTrend <= 0) &&                                   // 抖动趋势稳定或下降
        (metrics.arrivalRate >= m_config.arrivalRateUpThreshold) &&  // 到达率充足
        (metrics.queueDepth >= m_config.queueDepthUpThreshold) &&    // 队列充足
        (!trend.isPredictDrop);                                       // 没有预警
    
    if (!canUpgrade) {
        m_isStable = false;
        return std::nullopt;
    }
    
    // 检查稳定持续时间
    if (!m_isStable) {
        m_isStable = true;
        m_stableStartTime = metrics.timestamp;
        return std::nullopt;  // 刚开始稳定，等待
    }
    
    int64_t stableDuration = metrics.timestamp - m_stableStartTime;
    if (stableDuration < m_config.stableTimeMs) {
        return std::nullopt;  // 稳定时间不够
    }
    
    // ===== 升帧：每次升 upgradeStep fps =====
    int targetFps = currentFps + m_config.upgradeStep;
    
    // 不能超过到达帧率
    targetFps = std::min(targetFps, metrics.arrivalFps);
    // 不能超过原始帧率
    targetFps = std::min(targetFps, originFps);
    
    if (targetFps <= currentFps || targetFps == m_lastFps) {
        return std::nullopt;  // 无法升帧或与上次相同
    }
    
    // 重置稳定计时（下次升帧需要重新累积）
    m_isStable = false;
    
    SetFpsCommand cmd;
    cmd.fps = targetFps;
    cmd.urgency = Urgency::Low;  // 升帧用低优先级
    cmd.reason = Reason::NetworkRecover;
    cmd.bitrate = calculateBitrate(targetFps);
    cmd.timestamp = metrics.timestamp;
    
    return cmd;
}

void AdaptiveFpsDecider::reset() {
    m_isStable = false;
    m_stableStartTime = 0;
    m_lastFps = 0;
}

void AdaptiveFpsDecider::setConfig(const AdaptiveConfig& config) {
    m_config = config;
}

} // namespace adaptive

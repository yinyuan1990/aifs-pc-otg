/**
 * @file network_trend_predictor.cpp
 * @brief 网络趋势预测器实现
 */

#include "network_trend_predictor.h"
#include <algorithm>
#include <cmath>

namespace adaptive {

NetworkTrendPredictor::NetworkTrendPredictor(const AdaptiveConfig& config)
    : m_config(config)
{
}

void NetworkTrendPredictor::update(const NetworkMetrics& metrics) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    m_history.push_back({
        metrics.timestamp,
        metrics.jitterEma,
        metrics.arrivalRate,
        metrics.queueDepth,
        metrics.rttMs
    });
    
    // 只保留最近 HISTORY_SIZE 个样本
    while (m_history.size() > HISTORY_SIZE) {
        m_history.pop_front();
    }
}

TrendMetrics NetworkTrendPredictor::analyze() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    TrendMetrics trend = {0, 0, 0, 0, 0, false};
    
    if (m_history.size() < 2) {
        return trend;
    }
    
    // 取最新和最早的样本比较
    const auto& current = m_history.back();
    const auto& previous = m_history.front();
    
    double timeDelta = (current.timestamp - previous.timestamp) / 1000.0;  // 转换为秒
    if (timeDelta <= 0) {
        return trend;
    }
    
    // ===== 1. 计算抖动趋势 (ms/s) =====
    // 正值表示抖动在上升（网络恶化）
    trend.jitterTrend = (current.jitterEma - previous.jitterEma) / timeDelta;
    
    // ===== 2. 计算到达率斜率 (%/s) =====
    // 负值表示到达率在下降（网络恶化）
    trend.arrivalSlope = (current.arrivalRate - previous.arrivalRate) / timeDelta;
    
    // ===== 3. 计算队列下降速度 (帧/s) =====
    // 正值表示队列在减少（网络恶化）
    trend.queueDropSpeed = static_cast<int>((previous.queueDepth - current.queueDepth) / timeDelta);
    
    // ===== 4. 计算 RTT 趋势 (ms/s) =====
    // 正值表示 RTT 在上升（网络恶化）
    trend.rttTrend = (current.rttMs - previous.rttMs) / timeDelta;
    
    // ===== 5. 计算综合风险分 (0~1) =====
    // 各项指标归一化后加权求和
    
    // 抖动得分：10ms/s → 1.0
    double jitterScore = std::max(0.0, trend.jitterTrend / 10.0);
    
    // 到达率得分：-20%/s → 1.0
    double arrivalScore = std::max(0.0, -trend.arrivalSlope / 0.2);
    
    // 队列下降得分：6帧/s → 1.0
    double queueScore = std::max(0.0, static_cast<double>(trend.queueDropSpeed) / 6.0);
    
    // RTT 得分：200ms/s → 1.0
    double rttScore = std::max(0.0, trend.rttTrend / 200.0);
    
    // 加权求和（权重总和 = 1.0）
    trend.riskScore = 0.30 * jitterScore +    // 抖动最敏感，权重最高
                      0.25 * arrivalScore +   // 到达率
                      0.25 * queueScore +     // 队列下降
                      0.20 * rttScore;        // RTT
    
    trend.riskScore = std::min(1.0, trend.riskScore);
    
    // ===== 6. 判断是否需要预警 =====
    // 满足以下条件之一即触发预警：
    // - 风险分 > 阈值
    // - 满足 2 个或以上趋势预警条件
    
    int warningCount = 0;
    if (trend.jitterTrend > m_config.jitterTrendThreshold) warningCount++;
    if (trend.arrivalSlope < m_config.arrivalSlopeThreshold) warningCount++;
    if (trend.queueDropSpeed >= m_config.queueDropSpeedThreshold) warningCount++;
    if (trend.rttTrend > m_config.rttTrendThreshold) warningCount++;
    
    trend.isPredictDrop = (warningCount >= 2) || (trend.riskScore > m_config.riskScoreThreshold);
    
    return trend;
}

bool NetworkTrendPredictor::shouldPreemptiveDrop() const {
    return analyze().isPredictDrop;
}

void NetworkTrendPredictor::reset() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_history.clear();
}

} // namespace adaptive

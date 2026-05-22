/**
 * @file network_metrics.cpp
 * @brief 网络指标采集器实现
 */

#include "network_metrics.h"
#include <algorithm>
#include <cmath>

namespace adaptive {

NetworkMetricsCollector::NetworkMetricsCollector(int targetFps, const AdaptiveConfig& config)
    : m_targetFps(targetFps)
    , m_expectedIntervalMs(1000.0 / targetFps)
    , m_jitterAlpha(config.jitterEmaAlpha)
{
}

void NetworkMetricsCollector::onFrameArrived(int64_t timestampMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    // 1. 计算帧间隔抖动
    if (m_lastFrameTime > 0) {
        int64_t interval = timestampMs - m_lastFrameTime;
        calculateJitter(interval);
    }
    m_lastFrameTime = timestampMs;
    
    // 2. 记录帧时间戳
    m_frameTimestamps.push_back(timestampMs);
    while (m_frameTimestamps.size() > MAX_FRAME_HISTORY) {
        m_frameTimestamps.pop_front();
    }
    
    // 3. 计算到达率
    calculateArrivalRate();
}

void NetworkMetricsCollector::calculateJitter(int64_t intervalMs) {
    // 瞬时抖动 = |实际间隔 - 期望间隔|
    double jitter = std::abs(static_cast<double>(intervalMs) - m_expectedIntervalMs);
    
    // EMA 平滑：J_ema = α * J + (1-α) * J_ema
    m_jitterEma = m_jitterAlpha * jitter + (1.0 - m_jitterAlpha) * m_jitterEma;
}

void NetworkMetricsCollector::calculateArrivalRate() {
    if (m_frameTimestamps.size() < 2) {
        m_arrivalRate = 1.0;
        m_arrivalFps = m_targetFps;
        return;
    }
    
    // 计算最近 1 秒内的帧数
    int64_t now = m_frameTimestamps.back();
    int64_t oneSecondAgo = now - 1000;
    
    int frameCount = 0;
    for (auto it = m_frameTimestamps.rbegin(); it != m_frameTimestamps.rend(); ++it) {
        if (*it >= oneSecondAgo) {
            frameCount++;
        } else {
            break;
        }
    }
    
    m_arrivalFps = frameCount;
    m_arrivalRate = static_cast<double>(frameCount) / m_targetFps;
    
    // 限制范围
    m_arrivalRate = std::min(1.5, std::max(0.0, m_arrivalRate));
}

void NetworkMetricsCollector::updateRtcpStats(int packetsLost, int packetsReceived, int rttMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_packetsLost = packetsLost;
    m_packetsReceived = packetsReceived;
    m_rttMs = rttMs;
}

void NetworkMetricsCollector::updateQueueDepth(int depth) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_queueDepth = depth;
}

NetworkMetrics NetworkMetricsCollector::getMetrics() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    NetworkMetrics metrics;
    
    // 到达率
    metrics.arrivalRate = m_arrivalRate;
    metrics.arrivalFps = m_arrivalFps;
    
    // 抖动
    metrics.jitterEma = m_jitterEma;
    metrics.jitterRatio = (m_expectedIntervalMs > 0) ? (m_jitterEma / m_expectedIntervalMs) : 0.0;
    
    // 丢包率
    int total = m_packetsLost + m_packetsReceived;
    metrics.lossRate = (total > 0) ? (static_cast<double>(m_packetsLost) / total) : 0.0;
    
    // RTT
    metrics.rttMs = m_rttMs;
    
    // 队列深度
    metrics.queueDepth = m_queueDepth;
    
    // 时间戳
    metrics.timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    
    return metrics;
}

void NetworkMetricsCollector::setTargetFps(int fps) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_targetFps = fps;
    m_expectedIntervalMs = 1000.0 / fps;
}

} // namespace adaptive

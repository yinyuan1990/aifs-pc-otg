/**
 * @file network_metrics.h
 * @brief 网络指标采集器
 * @version 2.0
 * @date 2025-01-31
 */

#pragma once

#include "adaptive_fps_types.h"
#include <deque>
#include <mutex>
#include <chrono>

namespace adaptive {

/**
 * @class NetworkMetricsCollector
 * @brief 采集网络指标：到达率、抖动、队列深度、RTT、丢包率
 */
class NetworkMetricsCollector {
public:
    /**
     * @brief 构造函数
     * @param targetFps 目标帧率（用于计算期望帧间隔）
     * @param config 配置参数
     */
    explicit NetworkMetricsCollector(int targetFps = 60, const AdaptiveConfig& config = {});
    
    /**
     * @brief 帧到达时调用（每帧必调）
     * @param timestampMs 帧到达时间戳（毫秒）
     */
    void onFrameArrived(int64_t timestampMs);
    
    /**
     * @brief 更新 RTCP 统计
     * @param packetsLost 丢包数
     * @param packetsReceived 收包数
     * @param rttMs RTT（毫秒）
     */
    void updateRtcpStats(int packetsLost, int packetsReceived, int rttMs);
    
    /**
     * @brief 更新队列深度
     * @param depth 当前队列帧数
     */
    void updateQueueDepth(int depth);
    
    /**
     * @brief 获取当前网络指标
     * @return NetworkMetrics 当前指标快照
     */
    NetworkMetrics getMetrics() const;
    
    /**
     * @brief 设置目标帧率（帧率变化后调用）
     * @param fps 新的目标帧率
     */
    void setTargetFps(int fps);
    
    /**
     * @brief 获取当前目标帧率
     */
    int getTargetFps() const { return m_targetFps; }

private:
    void calculateJitter(int64_t intervalMs);
    void calculateArrivalRate();
    
    mutable std::mutex m_mutex;
    
    // 目标帧率相关
    int m_targetFps;
    double m_expectedIntervalMs;  // 1000.0 / targetFps
    
    // 帧到达时间记录
    std::deque<int64_t> m_frameTimestamps;
    static constexpr size_t MAX_FRAME_HISTORY = 120;  // 保留约 2 秒历史
    
    // 抖动计算
    double m_jitterEma = 0.0;
    double m_jitterAlpha;
    int64_t m_lastFrameTime = 0;
    
    // RTCP 统计
    int m_packetsLost = 0;
    int m_packetsReceived = 0;
    int m_rttMs = 0;
    
    // 队列深度
    int m_queueDepth = 9;
    
    // 到达率
    int m_arrivalFps = 60;
    double m_arrivalRate = 1.0;
};

} // namespace adaptive

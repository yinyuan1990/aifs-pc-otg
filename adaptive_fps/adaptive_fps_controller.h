/**
 * @file adaptive_fps_controller.h
 * @brief 自适应帧率总控制器
 * @version 2.0
 * @date 2025-01-31
 * 
 * 使用方法：
 * 1. 创建控制器，传入 WebSocket 发送回调
 * 2. 调用 start() 启动
 * 3. 每帧调用 onFrameArrived()
 * 4. 定期调用 updateQueueDepth() 和 updateRtcpStats()
 */

#pragma once

#include "adaptive_fps_types.h"
#include "network_metrics.h"
#include "network_trend_predictor.h"
#include "adaptive_fps_decider.h"
#include <memory>
#include <functional>
#include <thread>
#include <atomic>
#include <string>

namespace adaptive {

/**
 * @brief WebSocket 发送回调
 * @param json 要发送的 JSON 字符串
 * @return true 发送成功，false 发送失败
 */
using WsSendCallback = std::function<bool(const std::string&)>;

/**
 * @brief 日志回调（可选）
 * @param level 日志级别：0=debug, 1=info, 2=warn, 3=error
 * @param msg 日志内容
 */
using LogCallback = std::function<void(int level, const std::string& msg)>;

/**
 * @class AdaptiveFpsController
 * @brief 自适应帧率总控制器
 */
class AdaptiveFpsController {
public:
    /**
     * @brief 构造函数
     * @param originFps 原始帧率（最高帧率）
     * @param sendCallback WebSocket 发送回调
     * @param config 配置参数（可选）
     */
    AdaptiveFpsController(
        int originFps, 
        WsSendCallback sendCallback,
        const AdaptiveConfig& config = {}
    );
    
    ~AdaptiveFpsController();
    
    // 禁止拷贝
    AdaptiveFpsController(const AdaptiveFpsController&) = delete;
    AdaptiveFpsController& operator=(const AdaptiveFpsController&) = delete;
    
    /**
     * @brief 启动控制器（启动决策循环）
     */
    void start();
    
    /**
     * @brief 停止控制器
     */
    void stop();
    
    /**
     * @brief 是否正在运行
     */
    bool isRunning() const { return m_running; }
    
    // ==================== 外部接口 ====================
    
    /**
     * @brief 帧到达时调用（每帧必调）
     * @param timestampMs 帧到达时间戳（毫秒）
     */
    void onFrameArrived(int64_t timestampMs);
    
    /**
     * @brief 更新队列深度
     * @param depth 当前队列帧数
     */
    void updateQueueDepth(int depth);
    
    /**
     * @brief 更新 RTCP 统计
     * @param packetsLost 丢包数
     * @param packetsReceived 收包数
     * @param rttMs RTT（毫秒）
     */
    void updateRtcpStats(int packetsLost, int packetsReceived, int rttMs);
    
    // ==================== 状态查询 ====================
    
    /**
     * @brief 获取当前帧率
     */
    int getCurrentFps() const { return m_currentFps; }
    
    /**
     * @brief 获取原始帧率
     */
    int getOriginFps() const { return m_originFps; }
    
    /**
     * @brief 获取当前网络指标
     */
    NetworkMetrics getMetrics() const;
    
    /**
     * @brief 获取当前趋势指标
     */
    TrendMetrics getTrend() const;
    
    // ==================== 配置 ====================
    
    /**
     * @brief 设置日志回调
     */
    void setLogCallback(LogCallback callback);
    
    /**
     * @brief 更新配置
     */
    void setConfig(const AdaptiveConfig& config);
    
    /**
     * @brief 手动触发决策（通常不需要调用）
     */
    void triggerDecision();

private:
    void runLoop();
    std::string buildSetFpsJson(const SetFpsCommand& cmd);
    void log(int level, const std::string& msg);
    
    int m_originFps;
    std::atomic<int> m_currentFps;
    
    std::unique_ptr<NetworkMetricsCollector> m_metrics;
    std::unique_ptr<NetworkTrendPredictor> m_predictor;
    std::unique_ptr<AdaptiveFpsDecider> m_decider;
    
    WsSendCallback m_sendCallback;
    LogCallback m_logCallback;
    
    std::atomic<bool> m_running{false};
    std::thread m_loopThread;
    
    // 决策间隔（毫秒）
    static constexpr int DECISION_INTERVAL_MS = 1000;
};

} // namespace adaptive

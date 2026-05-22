/**
 * @file adaptive_fps_controller.cpp
 * @brief 自适应帧率总控制器实现
 */

#include "adaptive_fps_controller.h"
#include <sstream>
#include <chrono>
#include <iomanip>

namespace adaptive {

AdaptiveFpsController::AdaptiveFpsController(
    int originFps, 
    WsSendCallback sendCallback,
    const AdaptiveConfig& config)
    : m_originFps(originFps)
    , m_currentFps(originFps)
    , m_metrics(std::make_unique<NetworkMetricsCollector>(originFps, config))
    , m_predictor(std::make_unique<NetworkTrendPredictor>(config))
    , m_decider(std::make_unique<AdaptiveFpsDecider>(config))
    , m_sendCallback(std::move(sendCallback))
{
}

AdaptiveFpsController::~AdaptiveFpsController() {
    stop();
}

void AdaptiveFpsController::start() {
    if (m_running) {
        return;
    }
    
    m_running = true;
    m_loopThread = std::thread(&AdaptiveFpsController::runLoop, this);
    
    log(1, "AdaptiveFpsController started, originFps=" + std::to_string(m_originFps));
}

void AdaptiveFpsController::stop() {
    m_running = false;
    
    if (m_loopThread.joinable()) {
        m_loopThread.join();
    }
    
    log(1, "AdaptiveFpsController stopped");
}

void AdaptiveFpsController::runLoop() {
    while (m_running) {
        // 等待决策间隔
        std::this_thread::sleep_for(std::chrono::milliseconds(DECISION_INTERVAL_MS));
        
        if (!m_running) {
            break;
        }
        
        triggerDecision();
    }
}

void AdaptiveFpsController::triggerDecision() {
    // 1. 获取当前指标
    NetworkMetrics metrics = m_metrics->getMetrics();
    
    // 2. 更新趋势预测器
    m_predictor->update(metrics);
    TrendMetrics trend = m_predictor->analyze();
    
    // 3. 决策
    auto cmdOpt = m_decider->decide(metrics, trend, m_currentFps, m_originFps);
    
    // 4. 如果需要调整，发送指令
    if (cmdOpt && m_sendCallback) {
        std::string json = buildSetFpsJson(*cmdOpt);
        
        // 记录日志
        std::ostringstream logMsg;
        logMsg << "Sending set_fps: fps=" << cmdOpt->fps 
               << ", urgency=" << urgencyToString(cmdOpt->urgency)
               << ", reason=" << reasonToString(cmdOpt->reason)
               << " | metrics: arrivalRate=" << std::fixed << std::setprecision(2) << metrics.arrivalRate
               << ", jitterRatio=" << metrics.jitterRatio
               << ", queueDepth=" << metrics.queueDepth
               << " | trend: riskScore=" << trend.riskScore
               << ", isPredictDrop=" << (trend.isPredictDrop ? "true" : "false");
        log(1, logMsg.str());
        
        // 发送
        if (m_sendCallback(json)) {
            m_currentFps = cmdOpt->fps;
            m_metrics->setTargetFps(cmdOpt->fps);
            log(1, "set_fps sent successfully, new fps=" + std::to_string(cmdOpt->fps));
        } else {
            log(3, "Failed to send set_fps");
        }
    }
}

std::string AdaptiveFpsController::buildSetFpsJson(const SetFpsCommand& cmd) {
    std::ostringstream oss;
    oss << "{"
        << "\"cmd\":\"set_fps\","
        << "\"fps\":" << cmd.fps << ","
        << "\"urgency\":\"" << urgencyToString(cmd.urgency) << "\","
        << "\"reason\":\"" << reasonToString(cmd.reason) << "\","
        << "\"bitrate\":" << cmd.bitrate << ","
        << "\"timestamp\":" << cmd.timestamp
        << "}";
    return oss.str();
}

// ==================== 外部接口 ====================

void AdaptiveFpsController::onFrameArrived(int64_t timestampMs) {
    m_metrics->onFrameArrived(timestampMs);
}

void AdaptiveFpsController::updateQueueDepth(int depth) {
    m_metrics->updateQueueDepth(depth);
}

void AdaptiveFpsController::updateRtcpStats(int packetsLost, int packetsReceived, int rttMs) {
    m_metrics->updateRtcpStats(packetsLost, packetsReceived, rttMs);
}

// ==================== 状态查询 ====================

NetworkMetrics AdaptiveFpsController::getMetrics() const {
    return m_metrics->getMetrics();
}

TrendMetrics AdaptiveFpsController::getTrend() const {
    return m_predictor->analyze();
}

// ==================== 配置 ====================

void AdaptiveFpsController::setLogCallback(LogCallback callback) {
    m_logCallback = std::move(callback);
}

void AdaptiveFpsController::setConfig(const AdaptiveConfig& config) {
    m_decider->setConfig(config);
}

void AdaptiveFpsController::log(int level, const std::string& msg) {
    if (m_logCallback) {
        m_logCallback(level, msg);
    }
}

} // namespace adaptive

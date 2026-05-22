/**
 * @file example_usage.cpp
 * @brief 自适应帧率控制器使用示例
 * @version 2.0
 * @date 2025-01-31
 * 
 * 这个文件展示如何在你的播放器中集成 AdaptiveFpsController
 */

#include "adaptive_fps_controller.h"
#include <iostream>
#include <chrono>

// 假设你有一个 WebSocket 客户端类
class WebSocketClient {
public:
    bool send(const std::string& message) {
        std::cout << "[WebSocket] Sending: " << message << std::endl;
        return true;  // 实际实现中返回发送结果
    }
};

// 你的播放器类
class VideoPlayer {
public:
    VideoPlayer() : m_ws(std::make_unique<WebSocketClient>()) {}
    
    void init(int originFps = 60) {
        // 1. 创建配置（可选，使用默认值也可以）
        adaptive::AdaptiveConfig config;
        config.upgradeStep = 5;          // 每次升帧 5fps
        config.stableTimeMs = 1000;      // 稳定 1 秒后才升帧
        
        // 2. 创建控制器，传入 WebSocket 发送回调
        m_fpsController = std::make_unique<adaptive::AdaptiveFpsController>(
            originFps,
            [this](const std::string& json) {
                return m_ws->send(json);  // 通过 WebSocket 发送
            },
            config
        );
        
        // 3. 设置日志回调（可选）
        m_fpsController->setLogCallback([](int level, const std::string& msg) {
            const char* levelStr[] = {"DEBUG", "INFO", "WARN", "ERROR"};
            std::cout << "[" << levelStr[level] << "] " << msg << std::endl;
        });
        
        // 4. 启动控制器
        m_fpsController->start();
        
        std::cout << "VideoPlayer initialized with originFps=" << originFps << std::endl;
    }
    
    void shutdown() {
        if (m_fpsController) {
            m_fpsController->stop();
        }
    }
    
    // ==================== 在你的代码中调用这些方法 ====================
    
    /**
     * 每收到一帧时调用
     * 位置：解码后 / 渲染前
     */
    void onVideoFrameReceived() {
        auto now = std::chrono::steady_clock::now();
        int64_t timestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()).count();
        
        m_fpsController->onFrameArrived(timestampMs);
        
        // ... 你的渲染逻辑
    }
    
    /**
     * 队列深度更新时调用
     * 位置：缓冲队列变化时，每帧或定期调用
     */
    void onQueueUpdate(int queueDepth) {
        m_fpsController->updateQueueDepth(queueDepth);
    }
    
    /**
     * RTCP 统计更新时调用
     * 位置：WebRTC stats 回调，通常 1-5 秒一次
     */
    void onRtcpStatsUpdate(int packetsLost, int packetsReceived, int rttMs) {
        m_fpsController->updateRtcpStats(packetsLost, packetsReceived, rttMs);
    }
    
    // ==================== 状态查询 ====================
    
    int getCurrentFps() const {
        return m_fpsController ? m_fpsController->getCurrentFps() : 0;
    }
    
    void printStatus() {
        if (!m_fpsController) return;
        
        auto metrics = m_fpsController->getMetrics();
        auto trend = m_fpsController->getTrend();
        
        std::cout << "=== Adaptive FPS Status ===" << std::endl;
        std::cout << "Current FPS: " << m_fpsController->getCurrentFps() << std::endl;
        std::cout << "Arrival Rate: " << metrics.arrivalRate * 100 << "%" << std::endl;
        std::cout << "Jitter Ratio: " << metrics.jitterRatio * 100 << "%" << std::endl;
        std::cout << "Queue Depth: " << metrics.queueDepth << " frames" << std::endl;
        std::cout << "Risk Score: " << trend.riskScore << std::endl;
        std::cout << "Predict Drop: " << (trend.isPredictDrop ? "YES" : "NO") << std::endl;
        std::cout << "===========================" << std::endl;
    }

private:
    std::unique_ptr<WebSocketClient> m_ws;
    std::unique_ptr<adaptive::AdaptiveFpsController> m_fpsController;
};

// ==================== 主函数示例 ====================

int main() {
    std::cout << "=== Adaptive FPS Controller Example ===" << std::endl;
    
    VideoPlayer player;
    player.init(60);
    
    // 模拟接收帧
    std::cout << "\n--- Simulating frame reception ---" << std::endl;
    for (int i = 0; i < 10; i++) {
        player.onVideoFrameReceived();
        player.onQueueUpdate(9 - i % 3);  // 模拟队列变化
        std::this_thread::sleep_for(std::chrono::milliseconds(16));  // 约 60fps
    }
    
    // 打印状态
    player.printStatus();
    
    // 模拟弱网（队列下降）
    std::cout << "\n--- Simulating weak network ---" << std::endl;
    for (int i = 0; i < 5; i++) {
        player.onVideoFrameReceived();
        player.onQueueUpdate(3 - i);  // 队列快速下降
        std::this_thread::sleep_for(std::chrono::milliseconds(50));  // 帧间隔变大
    }
    
    // 等待决策循环执行
    std::this_thread::sleep_for(std::chrono::seconds(2));
    
    player.printStatus();
    player.shutdown();
    
    std::cout << "\n=== Example completed ===" << std::endl;
    return 0;
}

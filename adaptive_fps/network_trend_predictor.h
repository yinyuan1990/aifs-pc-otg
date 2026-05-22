/**
 * @file network_trend_predictor.h
 * @brief 网络趋势预测器 - 提前预知网络波动
 * @version 2.0
 * @date 2025-01-31
 * 
 * 核心功能：
 * - 通过监测指标的变化趋势，提前 1-2 秒预测网络恶化
 * - 主要监测：抖动趋势、到达率斜率、队列下降速度、RTT 趋势
 */

#pragma once

#include "adaptive_fps_types.h"
#include <deque>
#include <mutex>

namespace adaptive {

/**
 * @class NetworkTrendPredictor
 * @brief 网络趋势预测器
 * 
 * 通过分析历史数据的变化趋势，预测网络状态：
 * - 抖动趋势 > 2ms/s → 网络正在恶化
 * - 到达率斜率 < -5%/s → 网络即将恶化
 * - 队列下降 >= 3帧/s → 1-2秒后见底
 * - RTT趋势 > 50ms/s → 拥塞加剧
 */
class NetworkTrendPredictor {
public:
    /**
     * @brief 构造函数
     * @param config 配置参数
     */
    explicit NetworkTrendPredictor(const AdaptiveConfig& config = {});
    
    /**
     * @brief 更新历史数据（每秒调用一次）
     * @param metrics 当前网络指标
     */
    void update(const NetworkMetrics& metrics);
    
    /**
     * @brief 分析趋势，返回预测结果
     * @return TrendMetrics 趋势指标
     */
    TrendMetrics analyze() const;
    
    /**
     * @brief 是否需要预警降帧
     * @return true 表示预测到网络即将恶化，应提前降帧
     */
    bool shouldPreemptiveDrop() const;
    
    /**
     * @brief 重置历史数据
     */
    void reset();

private:
    struct Sample {
        int64_t timestamp;
        double jitterEma;
        double arrivalRate;
        int queueDepth;
        int rttMs;
    };
    
    std::deque<Sample> m_history;
    AdaptiveConfig m_config;
    mutable std::mutex m_mutex;
    
    // 保留 5 秒历史用于趋势分析
    static constexpr size_t HISTORY_SIZE = 5;
};

} // namespace adaptive

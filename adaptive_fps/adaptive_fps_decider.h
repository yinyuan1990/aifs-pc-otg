/**
 * @file adaptive_fps_decider.h
 * @brief 自适应帧率决策器
 * @version 2.0
 * @date 2025-01-31
 * 
 * 决策策略：
 * - 降帧：快降、大幅度（四档跳）
 * - 升帧：慢升、小幅度（5fps/次，间隔>=1秒）
 */

#pragma once

#include "adaptive_fps_types.h"
#include <optional>

namespace adaptive {

/**
 * @class AdaptiveFpsDecider
 * @brief 帧率决策器
 * 
 * 降帧优先级（从高到低）：
 * 1. 队列即将见底（<=1帧）→ critical, 直接降到 15fps
 * 2. 预测式预警（风险分>0.5）→ high, 降一档或两档
 * 3. 到达率不足（<85%）→ normal, 降到到达帧率
 * 4. 抖动过高（>30%）→ normal, 降一档
 * 5. 队列不足（<3帧）→ normal, 降一档
 * 6. RTT过高（>300ms）→ normal, 降一档
 * 
 * 升帧条件（全部满足）：
 * - 抖动比例 < 20%
 * - 抖动趋势稳定或下降
 * - 到达率 >= 90%
 * - 队列 >= 5帧
 * - 稳定持续 >= 1秒
 */
class AdaptiveFpsDecider {
public:
    /**
     * @brief 构造函数
     * @param config 配置参数
     */
    explicit AdaptiveFpsDecider(const AdaptiveConfig& config = {});
    
    /**
     * @brief 决策：是否需要发送 set_fps 指令
     * @param metrics 当前网络指标
     * @param trend 趋势指标
     * @param currentFps 当前帧率
     * @param originFps 原始帧率（上限）
     * @return std::optional<SetFpsCommand> 如果需要调整，返回指令；否则返回 nullopt
     */
    std::optional<SetFpsCommand> decide(
        const NetworkMetrics& metrics,
        const TrendMetrics& trend,
        int currentFps,
        int originFps
    );
    
    /**
     * @brief 重置状态（重新开始）
     */
    void reset();
    
    /**
     * @brief 更新配置
     * @param config 新配置
     */
    void setConfig(const AdaptiveConfig& config);

private:
    /**
     * @brief 降帧决策
     */
    std::optional<SetFpsCommand> decideDropFps(
        const NetworkMetrics& metrics, 
        const TrendMetrics& trend,
        int currentFps);
    
    /**
     * @brief 升帧决策
     */
    std::optional<SetFpsCommand> decideUpgradeFps(
        const NetworkMetrics& metrics, 
        const TrendMetrics& trend,
        int currentFps, 
        int originFps);
    
    AdaptiveConfig m_config;
    
    // 升帧状态
    bool m_isStable = false;           // 是否处于稳定状态
    int64_t m_stableStartTime = 0;     // 稳定开始时间
    int m_lastFps = 0;                 // 上次帧率（用于避免重复发送）
};

} // namespace adaptive

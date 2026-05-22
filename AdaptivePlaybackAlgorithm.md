# 自适应播放算法 - 完整数学模型

> **核心原则：延迟按时间(ms)恒定，队列帧数根据 FPS 自动调整**
> 
> **最后更新：2026-01-31**
> - **v8.1 (当前版本)**: ⭐⭐⭐ **回滚到稳定版本**
>   - 去掉帧波动检测（v8.3-v8.5的实验功能）
>   - **降帧条件**：水位<35% 或 延迟>80ms（双条件平滑降帧）⭐80ms更早介入
>   - **升帧条件**：延迟80-280ms + 水位≥50% + 持续1秒 + 不超过到达帧率
>   - **渲染间隔**：基于到达帧率EMA，EMA本身已平滑
> - ~~v8.5~~: 渲染间隔只基于配置帧率（已回滚，仍有问题）
> - ~~v8.4~~: 到达帧率监测（已回滚）
> - ~~v8.3~~: 帧间隔抖动预测（已回滚）
> - v8.2: 动态阈值 + 预测式降帧（备用方案）
> - v8: 客户方案（以帧率百分比计算队列目标）
> - v7: 对齐Java版本（增大jitterbuffer延迟，减小应用层队列）
> - v6: 紧急保护修复 + 低fps升帧逻辑优化
> - v5: 追帧优化 + 恢复阈值优化 + 队列过满丢帧机制
> - v4: 第二道防线优化（触发更早）
> - v3: 新增第二道防线（推流帧率控制）
> - v2: 修复队列振荡导致卡顿
> - v1: 修复FPS无法恢复

---

## 1. 系统变量定义

### 1.1 输入变量
| 变量 | 符号 | 单位 | 说明 |
|------|------|------|------|
| 推流FPS | `F_push` | fps | iOS/PC 推流帧率（可变：3-120fps）|
| 实际到达帧数 | `N_arrive(t)` | 帧/秒 | 每秒实际到达的帧数（受网络影响）|
| 当前队列深度 | `Q(t)` | 帧 | 应用层缓冲队列中的帧数 |
| 配置FPS | `F_config` | fps | PC手动设置或iOS告知的目标FPS |

### 1.2 状态变量
| 变量 | 符号 | 范围 | 说明 |
|------|------|------|------|
| 队列目标 | `Q_target` | [3, 36]帧 | **动态计算** = FPS × DELAY_PER_FPS / 1000 |
| 播放速率 | `R_play` | [0.7, 1.2] | 播放速率乘数 |
| 水位 | `W(t)` | [0, ∞) | = Q(t) / Q_target |
| 到达速率EMA | `F_ema` | fps | 平滑后的帧到达速率 |
| 紧急保护标志 | `E_hold` | {0, 1} | 1=停止消耗，保留最后帧 |
| 稳定计数器 | `C_stable` | ≥0 | 用于判断网络是否稳定 |

### 1.3 常量参数
```
⭐⭐⭐ 延迟控制（v7 优化：对齐Java版本）：
DELAY_PER_FPS = 5ms            // ⭐ v7: 10→5（减半，缩小应用层队列）
DELAY_MIN = 100ms              // 最小应用层延迟
DELAY_MAX = 200ms              // ⭐ v7: 600→200（大幅缩小应用层延迟）
GST_JITTER_LATENCY = 350ms     // ⭐ v7: 100→350（增大GStreamer内部缓冲）
appsink max-buffers = 30       // ⭐ v7: 1→30（让GStreamer内部管理缓冲）

队列范围（v7 优化后）：
QUEUE_MIN = 3帧                // 最小队列（保持不变）
QUEUE_MAX = 12帧               // ⭐ v7: 36→12（60fps×200ms=12帧）

队列调整参数：
QUEUE_EXPAND_STEP = 3帧        // 每次扩容步长
QUEUE_SHRINK_STEP = 1帧        // 每次收缩步长
SHRINK_STABLE_FRAMES = 90帧    // 稳定3秒才收缩（旧值60帧/2秒）

⭐ 防振荡参数（v2 新增）：
FAST_ADJUST_THRESHOLD = 6帧    // 快速调整阈值（旧值3帧）
EXPAND_COOLDOWN_SEC = 3秒      // 扩容后冷却期，禁止快速收缩

水位阈值（分段函数）：
W_EMERGENCY    = 0.15          // 紧急水位（停止消耗）
W_EXPAND       = 0.35          // 扩容水位
W_NORMAL_LOW   = 0.50          // 正常低水位（恢复消耗/解除紧急）
W_NORMAL_HIGH  = 0.80          // 正常高水位（触发收缩）
W_CATCHUP      = 1.05          // 追帧水位
W_CATCHUP_MAX  = 1.50          // 最大追帧水位

⭐ 队列过满丢帧阈值（v5 新增）：
W_DROP_THRESHOLD = 1.50        // 水位>150%时触发丢帧
W_DROP_TARGET    = 1.20        // 丢帧后目标水位120%

速率范围（v5 优化）：
R_MIN = 0.7                    // 最低速率70%
R_MAX = 1.1                    // ⭐ 1.2→1.1：追帧更温和
R_CHANGE_LIMIT = 0.05          // 每帧最大变化±5%（导数限制）

FPS变化检测：
FPS_CHANGE_THRESHOLD = 0.3     // FPS变化阈值30%
FPS_CHANGE_STABLE_SEC = 3      // 持续3秒才触发重配置
FPS_MIN = 3fps                 // ⭐ 最小支持帧率（支持极低fps场景）

⭐⭐⭐ 第二道防线参数（v6 优化：低fps固定队列升帧）：
W_REQUEST_LOWER_FPS = 0.30     // ⭐ v4: 20→30%：水位<30%触发降帧（更早介入）
W_REQUEST_RESTORE_FPS = 0.50   // ⭐ v5: 80→50%：高fps场景用水位判断
FPS_LOWER_HOLD_SEC = 1秒       // ⭐ v4: 2→1秒：更快响应
FPS_RESTORE_HOLD_SEC = 5秒     // ⭐ v5: 3→5秒：防止反复触发
FPS_PUSH_MIN = 10fps           // 推流帧率下限（实际fps）
RESTORE_QUEUE_THRESHOLD = 5帧  // ⭐ v6: 低fps升帧的固定队列阈值
LOW_FPS_THRESHOLD = 20fps      // ⭐ v6: 低fps场景判断阈值

⭐⭐⭐ FPS换算关系（重要！）：
服务器fps = 实际fps × 4
iOS收到服务器fps后会除以4
滑块范围: 40-240 (服务器fps) = 10-60fps (实际)
```

---

## 2. 核心方程

### 2.1 ⭐ 延迟计算（v7优化：对齐Java版本）

```
应用层延迟 = clamp(FPS × DELAY_PER_FPS, DELAY_MIN, DELAY_MAX)
          = clamp(FPS × 5ms, 100ms, 200ms)    // ⭐ v7: 10→5, 600→200

队列帧数 = FPS × 应用层延迟 / 1000
        = clamp(结果, QUEUE_MIN, QUEUE_MAX)  // ⭐ v7: QUEUE_MAX=12

总延迟 = GST_JITTER_LATENCY + 应用层延迟
       = 350ms + (100~200ms)                  // ⭐ v7: jitter 100→350
       = 450~550ms  ← 与Java版本（300-400ms）接近！

⭐ v2设计思想：用略高延迟换取极端网络下的流畅体验
```

**验证：不同FPS的延迟一致性（v2优化后）**
| FPS | 应用层延迟 | 队列帧数 | 每帧时长 | 总延迟 |
|-----|-----------|---------|---------|-------|
| 5fps | max(100, 5×10) = **100ms** | 5×100/1000 = **0.5→3帧** | 200ms | **700ms** |
| 15fps | 15×10 = **150ms** | 15×150/1000 = **2.25→3帧** | 66.7ms | **300ms** |
| 30fps | 30×10 = **300ms** | 30×300/1000 = **9帧** | 33.3ms | **400ms** |
| 60fps | min(600, 60×10) = **600ms** | 60×600/1000 = **36帧** | 16.7ms | **700ms** |
| 120fps | min(600, 120×10) = **600ms** | 120×600/1000 = **72→36帧** | 8.3ms | **400ms** |

> ⭐ v2优化：增加缓冲深度，用略高延迟换取极端网络下的流畅体验

### 2.2 水位计算
```
W(t) = Q(t) / Q_target
```

### 2.3 渲染间隔计算 (v8.1当前版本)

**⭐⭐⭐ v8.1：基于到达帧率EMA计算渲染间隔**

```
// v8.1: 基于到达帧率EMA（已平滑）
T_render = (1000 / F_ema) / R_play  [ms]

例：F_ema=30fps, R_play=1.0  → T_render = 33.3ms
    F_ema=30fps, R_play=0.7  → T_render = 47.6ms (慢放)
    F_ema=30fps, R_play=1.2  → T_render = 27.8ms (追帧)

// 到达帧率EMA计算
F_ema = α × F_current + (1-α) × F_ema   (α=0.3)

// EMA的优点：自动平滑网络抖动，不需要额外处理
```

**v8.1选择到达帧率EMA的原因**：
1. EMA本身已有平滑效果（α=0.3，约3帧平滑）
2. 自动适应实际网络状况
3. 降帧后消耗速度自动降低（不会耗尽队列）

---

## 3. 分段函数 - 播放速率控制

### 3.1 水位-速率映射（核心分段函数，v5更新）

```
                    ┌ 0.7                              , W < 0.15 (紧急)
                    │ 0.7 + 0.3×(W-0.15)/0.2           , 0.15 ≤ W < 0.35 (恢复)
R_target(W) =       │ 1.0                              , 0.35 ≤ W < 1.05 (正常)
                    │ 1.0 + 0.1×(W-1.05)/0.45          , 1.05 ≤ W < 1.5 (追帧) ⭐ v5: 0.2→0.1
                    └ 1.1                              , W ≥ 1.5 (最大追帧) ⭐ v5: 1.2→1.1
```

### 3.2 图示（v5更新：追帧更温和）
```
R_play
  ^
1.2├ - - - - - - - - - - - - - - - - - - - - - - - - (旧最大)
1.1├──────────────────────────────────────●━━━━━━━━━━ ⭐ v5新最大
   │                                    ╱
1.0├────────────●━━━━━━━━━━━━━━━━━━━━━●
   │          ╱
0.7├━━━━━●━━●
   │     ↑紧急
   └─────┼─────┼─────┼─────┼─────┼─────┼───→ W (水位)
        0.15  0.35  0.5  0.8  1.05  1.5
         │     │     │     │     │     │
      紧急   扩容  解除  收缩   追帧  丢帧⭐v5
                 紧急
```

### 3.3 C++ 实现（v5更新）
```cpp
double GstPlayer::piecewiseRate(double W) {
    if (W < W_EMERGENCY) {
        return R_MIN;  // 0.7
    } else if (W < W_EXPAND) {
        // 线性恢复：0.7 → 1.0
        double t = (W - W_EMERGENCY) / (W_EXPAND - W_EMERGENCY);
        return R_MIN + (1.0 - R_MIN) * t;
    } else if (W < W_CATCHUP) {
        return 1.0;  // 正常
    } else if (W < W_CATCHUP_MAX) {
        // 线性追帧：1.0 → 1.1 ⭐ v5: 最大1.1（原1.2）
        double t = (W - W_CATCHUP) / (W_CATCHUP_MAX - W_CATCHUP);
        return 1.0 + (R_MAX - 1.0) * t;
    } else {
        return R_MAX;  // 1.1 ⭐ v5: 追帧更温和
    }
}
```

---

## 4. 导数限制 - 变化率控制（平滑）

### 4.1 速率变化限制
```
delta = clamp(R_target - R_current, -0.05, +0.05)
R_new = R_current + delta

即：每帧最多变化 ±5%
    @ 30fps 约需 6帧(200ms) 从 0.7 变到 1.0
```

### 4.2 队列目标变化
```
dQ_target/dt:
  - 扩容：+3帧/秒 (快速响应网络恶化)
  - 收缩：-1帧/秒 (缓慢恢复，需稳定60帧≈2秒)
  - 紧急保护：立即降到 QUEUE_MIN（3帧）⭐
```

### 4.3 渲染间隔平滑（v8.1标准EMA）
```
// v8.1: 标准EMA平滑
T_smooth(t) = γ × T_raw + (1-γ) × T_smooth(t-1)
            , γ = 0.3  (约3帧平滑)

T_final = clamp(T_smooth, T_prev±15%, [8, 200]ms)

// 为什么用15%变化限制？
// - 太小（如2%）→ 帧率变化时追不上，队列持续积压或耗尽
// - 太大（如50%）→ 间隔跳变，用户感觉卡顿
// - 15%是平衡点，既能跟上帧率变化，又不会太跳跃
```

---

## 5. 状态转移图

```
                         ┌─────────────────┐
                         │    INITIAL      │
                         │  (等待首帧)     │
                         └────────┬────────┘
                                  │ Q ≥ Q_target
                                  ▼
       ┌──────────────────────────────────────────────────────┐
       │                    PLAYING                           │
       │  ┌─────────┐     ┌─────────┐     ┌─────────┐        │
       │  │EMERGENCY│◄───►│ NORMAL  │◄───►│ CATCHUP │        │
       │  │ W<0.15  │     │0.35≤W≤  │     │ W>1.05  │        │
       │  │R=0.7    │     │1.05     │     │R=1.0~1.2│        │
       │  │停止消耗 │     │R=1.0    │     │加速消耗 │        │
       │  │Q→3帧⭐  │     └────┬────┘     └────┬────┘        │
       │  └────┬────┘          │               │             │
       │       │   W>0.5解除   │               │             │
       │       │  ┌────────────┴───────────┐   │             │
       │       └─►│     RECOVERY           │◄──┘             │
       │          │   R=0.7→1.0 渐变       │                 │
       │          │   速度平滑恢复中        │                 │
       │          └────────────────────────┘                 │
       └──────────────────────────────────────────────────────┘
```

---

## 6. ⭐ 紧急保护机制（v6 关键修复）

### 6.1 激活条件（v6 修复）
```
⭐⭐⭐ v6修复：改为队列真正为0时才触发紧急保护

旧逻辑（v5及之前）：
  条件：queueDepth == 1（取最后1帧时触发）
  问题：在10fps场景下（队列目标5帧），频繁触发/解除
        日志显示"水位40%正常"却"紧急保护激活"，令人困惑

新逻辑（v6）：
  条件：queueDepth == 0（队列完全耗尽时触发）
  
  取帧逻辑：
    if (queueDepth > 1)  → 正常取帧
    if (queueDepth == 1) → 正常取帧（不触发紧急保护）
    if (queueDepth == 0) → 触发紧急保护，使用最后有效帧

处理：
  1. E_hold = true（停止消耗队列）
  2. Q_target → QUEUE_MIN（3帧）⭐ 关键！
  3. 渲染最后有效帧（防止马赛克）
  4. R_play 通过分段函数逐步降到 0.7
```

### 6.1.1 v6 问题分析：为什么 queueDepth==1 触发会导致频繁振荡？
```
场景：10fps 推流，队列目标5帧

时间线分析：
  帧到达间隔 = 1000/10 = 100ms
  渲染间隔 ≈ 93ms（基于EMA计算，略快）

  T+0ms:   队列=3帧，消耗1帧 → 队列=2帧
  T+93ms:  队列=2帧，消耗1帧 → 队列=1帧
  T+100ms: 新帧到达 → 队列=2帧
  T+186ms: 队列=2帧，消耗1帧 → 队列=1帧  ← 旧逻辑触发紧急保护！
  T+200ms: 新帧到达 → 队列=2帧 → 水位40%>15% → 不解除
  T+279ms: 队列=2帧，消耗1帧 → 队列=1帧 → 紧急保护已激活，不重复触发
  T+300ms: 新帧到达 → 队列=3帧 → 水位60%>50% → 解除紧急保护
  T+372ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+400ms: 新帧到达 → 队列=3帧
  T+465ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+500ms: 新帧到达 → 队列=3帧
  T+558ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+600ms: 新帧到达 → 队列=3帧
  T+651ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+700ms: 新帧到达 → 队列=3帧
  T+744ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+800ms: 新帧到达 → 队列=3帧
  T+837ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+900ms: 新帧到达 → 队列=3帧
  T+930ms: 队列=3帧，消耗1帧 → 队列=2帧
  T+1000ms: 新帧到达 → 队列=3帧
  ...
  T+~1200ms: 队列从3→2→1 → 紧急保护触发（循环）

结果：每1-2秒触发一次紧急保护/解除
      日志显示"水位40%正常"和"紧急保护"交替出现

v6修复后：
  只有 queueDepth==0 才触发紧急保护
  正常情况下队列在2-3帧波动，不会触发
```

### 6.2 ⭐ 为什么要降低 Q_target？
```
问题场景：
  - 网络完全中断，到达=0fps
  - 紧急保护激活，Q_target=30帧
  - 解除条件：W > 50%，需要 15 帧
  - 但没有新帧进来 → 永远无法解除！

修复方案：
  - 紧急保护激活时，Q_target 立即降到 3 帧
  - 解除只需要 2 帧（3×50%=1.5）
  - 网络恢复后，2帧到达即可解除
```

### 6.3 解除条件
```
条件：W > 0.5（水位恢复到50%以上）
处理：
  1. E_hold = false
  2. R_play 通过分段函数平滑恢复到 1.0
  3. Q_target 可以重新根据网络状况扩容
```

### 6.4 网络中断恢复时序
```
T=0s:  正常播放，Q_target=9帧
T=1s:  网络中断，到达=0fps
T=2s:  队列耗尽，W<15%，紧急保护激活
       → Q_target 30→3帧，停止消耗
T=5s:  网络恢复，帧开始到达
T=5.1s: 队列=2帧，W=66%>50%，自动解除紧急保护
T=6s:  恢复正常播放
```

---

## 6.5 ⭐⭐⭐ EMA强制同步机制（2025-01-26 关键修复）

### 问题描述
```
原有问题：
  - 网络断开时（到达=0fps），on_newSample 不执行
  - m_arrivalRateEma 保持在断开前的值（如60fps）
  - adjustQueueTarget 使用 m_arrivalRateEma=60 计算队列目标=18帧
  - 实际到达=0，水位=0%，触发紧急保护
  - 网络恢复后 EMA 仍为 60fps，队列目标仍为 18帧
  - 即使帧到达，水位上升缓慢，导致 FPS 无法恢复

根本原因：
  EMA 只在 on_newSample（有帧到达时）更新
  当网络断开时，EMA 值"冻结"，与实际情况严重脱节
```

### 解决方案：on_timeout 中强制同步 EMA
```cpp
// 每秒执行一次（1Hz决策频率）
void on_timeout() {
    m_lastSecondFps = m_currentSecondFrames;  // 统计上一秒到达帧数
    m_currentSecondFrames = 0;
    
    // ⭐⭐⭐ 关键修复：强制同步 EMA 与实际到达帧率
    if (m_lastSecondFps == 0) {
        // 网络完全断开：快速衰减 EMA 到最小值
        if (m_arrivalRateEma > 3.0) {
            m_arrivalRateEma = max(3.0, m_arrivalRateEma * 0.3);  // 每秒衰减70%
            log("📉 网络断开检测 | EMA衰减到 " + m_arrivalRateEma + "fps");
        }
    } else if (m_lastSecondFps > 0 && m_arrivalRateEma > 0) {
        // 有帧到达：检查 EMA 与实际到达帧率的偏差
        double ratio = m_lastSecondFps / m_arrivalRateEma;
        if (ratio < 0.3 || ratio > 3.0) {
            // 偏差超过3倍：立即重置 EMA
            m_arrivalRateEma = m_lastSecondFps;
            log("⚡ EMA强制重置为 " + m_arrivalRateEma + "fps | 偏差=" + ratio + "x");
        }
    }
    
    // 后续调用 adjustQueueTarget / adjustPlaybackRate
}
```

### EMA衰减示例（网络断开场景）
```
T=0s:  正常60fps，EMA=60
T=1s:  网络断开，到达=0fps
       → EMA = 60 × 0.3 = 18fps
T=2s:  仍断开，到达=0fps
       → EMA = 18 × 0.3 = 5.4fps
T=3s:  仍断开，到达=0fps
       → EMA = 5.4 × 0.3 = 1.6 → clamp(3.0) = 3fps
T=4s:  EMA稳定在3fps，队列目标=3帧
T=5s:  网络恢复，到达=15fps
       → ratio = 15/3 = 5.0 > 3.0 → EMA立即重置为15fps
T=6s:  到达=30fps
       → ratio = 30/15 = 2.0 < 3.0 → 正常EMA平滑更新
```

### 配合 adjustQueueTarget 的改进
```cpp
void adjustQueueTarget(int queueDepth) {
    // ⭐⭐⭐ 使用实际到达帧率（EMA）而非配置帧率
    double actualFps = m_arrivalRateEma > 3.0 ? m_arrivalRateEma : m_configFps;
    
    // ⭐⭐⭐ 基于实际fps计算队列上限（最多500ms应用层延迟）
    // 防止低fps时队列盲目扩容到30帧导致延迟爆表
    int fpsBasedMax = actualFps * 500 / 1000;  // 500ms延迟上限
    fpsBasedMax = clamp(fpsBasedMax, QUEUE_MIN, QUEUE_MAX);
    
    // ⭐⭐⭐ FPS突变时快速调整（差距>3帧时每秒调整50%）
    int idealTarget = actualFps * appDelayMs / 1000;
    int targetDiff = idealTarget - m_queueTarget;
    if (abs(targetDiff) > 3) {
        int step = targetDiff / 2;
        m_queueTarget += step;
        m_queueTarget = clamp(m_queueTarget, QUEUE_MIN, fpsBasedMax);
        return;  // 快速调整时跳过常规扩容/收缩逻辑
    }
    
    // 常规扩容/收缩逻辑...
}
```

---

## 6.6 ⭐⭐⭐ 防队列振荡机制（2025-01-26 v2优化）

### 问题描述
```
极端网络下观察到的现象：
  01:35:35 📈 队列扩容 | 7→10帧 | 水位=28%
  01:35:36 ⚡ 队列快速调整 | 10→7帧 | 理想=4帧
  01:35:37 📈 队列扩容 | 7→10帧 | 水位=14%
  01:35:38 ⚡ 队列快速调整 | 10→7帧 | 理想=4帧
  → 每秒振荡1-2次，导致卡顿！

根本原因：两套机制在"打架"
  1. 水位扩容：水位<35%时扩容（+3帧）
  2. FPS快速调整：理想目标=4帧，立即收缩
  
旧算法计算（30fps）：
  appDelayMs = 30 × 5ms = 150ms
  idealTarget = 30 × 150 / 1000 = 4.5 → 4帧
  → 理想目标太小，与水位扩容冲突
```

### 解决方案

#### 1. 增加缓冲深度
```
参数调整：
  DELAY_PER_FPS: 5ms → 10ms
  DELAY_MIN: 50ms → 100ms
  DELAY_MAX: 500ms → 600ms
  QUEUE_MIN: 1帧 → 3帧
  QUEUE_MAX: 30帧 → 36帧

新算法计算（30fps）：
  appDelayMs = 30 × 10ms = 300ms
  idealTarget = 30 × 300 / 1000 = 9帧
  → 理想目标与水位扩容一致，不再冲突
```

#### 2. 增加快速调整阈值
```
旧值：差距 > 3帧 触发快速调整
新值：差距 > 6帧 触发快速调整

效果：
  旧: |10 - 4| = 6 > 3 → 触发收缩
  新: |12 - 9| = 3 ≤ 6 → 不触发，保持稳定
```

#### 3. 扩容冷却机制
```cpp
static qint64 lastExpandTime = 0;
qint64 now = QDateTime::currentMSecsSinceEpoch();
bool inCooldown = (now - lastExpandTime) < (EXPAND_COOLDOWN_SEC * 1000);

// 冷却期内禁止快速收缩
if (inCooldown && targetDiff < 0) {
    shouldFastAdjust = false;
}

// 扩容时记录时间
if (扩容发生) {
    lastExpandTime = now;
}
```

### 效果对比
```
优化前（30fps极端网络）：
  理想目标=4帧, 队列在7-10帧之间振荡
  → 每秒振荡1-2次 → 明显卡顿
  → 延迟约200ms但不流畅

优化后（30fps极端网络）：
  理想目标=9帧, 队列稳定在9-12帧
  → 扩容后3秒冷却 → 稳定运行
  → 延迟约400ms但流畅

延迟 vs 流畅度权衡：
  用 ~200ms 额外延迟，换取极端网络下的流畅体验
  竞品（如XX）在极端网络下延迟约500-800ms，但非常流畅
```

---

## 7. ⭐⭐⭐ 第二道防线：推流帧率控制（v3新增）

### 7.1 设计目的
```
当本地播放速度调节（第一道防线）无法解决网络拥塞时，
通知iOS前端降低推流帧率，从源头减轻网络压力。

三道防线体系：
  第一道：队列扩缩（adjustQueueTarget）
  第二道：推流帧率控制（checkPushFpsControl）⭐ 新增
  第三道：播放速率调节（piecewiseRate）
```

### 7.2 ⭐⭐⭐ FPS换算关系（重要！）
```
┌─────────────────────────────────────────────────────────────┐
│  服务器fps = 实际fps × 4                                     │
│  iOS收到后会除以4                                            │
│                                                             │
│  滑块范围: 40-240 (服务器fps)                                │
│  对应实际: 10-60fps (iOS推流)                                │
│                                                             │
│  例：滑块设置240 → 服务器存240 → iOS收到240/4=60fps推流      │
│  例：滑块设置40  → 服务器存40  → iOS收到40/4=10fps推流       │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 触发条件
```cpp
// 前置检查：必须有有效帧数据才触发（防止无推流时误触发）
if (m_lastSecondFps <= 0) {
    // 无数据，重置计数器，不触发
    return;
}

// 低水位检测：请求降低推流帧率
if (W < W_REQUEST_LOWER_FPS) {  // 水位<30% (v4优化：更早介入)
    lowWaterHoldSec++;
    if (lowWaterHoldSec >= FPS_LOWER_HOLD_SEC) {  // 持续1秒 (v4优化：更快响应)
        // 触发降帧请求
    }
}

// 高水位检测：请求恢复推流帧率
if (W > W_REQUEST_RESTORE_FPS && requestedFps > 0) {  // 水位>80%
    highWaterHoldSec++;
    if (highWaterHoldSec >= FPS_RESTORE_HOLD_SEC) {  // 持续3秒
        // 触发恢复请求
    }
}
```

### 7.4 分级降帧策略
```
实际fps降帧阶梯：60 → 45 → 30 → 20 → 15 → 10（最低）
服务器fps对应：  240 → 180 → 120 → 80 → 60 → 40

┌───────────┬───────────┬───────────────────────────────────┐
│ 实际fps   │ 服务器fps │ 说明                              │
├───────────┼───────────┼───────────────────────────────────┤
│ 60fps     │ 240       │ 初始/用户设置最大                 │
│ 45fps     │ 180       │ 第一次降帧                        │
│ 30fps     │ 120       │ 第二次降帧                        │
│ 20fps     │ 80        │ 第三次降帧                        │
│ 15fps     │ 60        │ 第四次降帧                        │
│ 10fps     │ 40        │ 最低限（FPS_PUSH_MIN）            │
└───────────┴───────────┴───────────────────────────────────┘

C++代码（gstplayer.cpp）：
  // m_configFps 是服务器fps格式，先转换为实际fps
  int actualCurrentFps = currentFps / 4;
  int targetActualFps = FPS_PUSH_MIN;  // 默认最低10fps
  if (actualCurrentFps > 45) targetActualFps = 45;
  else if (actualCurrentFps > 30) targetActualFps = 30;
  else if (actualCurrentFps > 20) targetActualFps = 20;
  else if (actualCurrentFps > 15) targetActualFps = 15;
  else if (actualCurrentFps > FPS_PUSH_MIN) targetActualFps = FPS_PUSH_MIN;
  
  // 发送给QML的是实际fps，QML会×4转换为服务器fps
  emit requestFpsChange(targetActualFps);

QML代码（MainPage.qml）：
  onRequestFpsChange: function(targetFps) {
      // targetFps是实际fps，需要×4转换为服务器fps
      var serverFps = targetFps * 4
      HttpClient.updateFps(serverFps)
      sendConfigUpdate("fps", {"fps": serverFps})
  }
```

### 7.5 恢复机制（v6 重大修复：低fps固定队列升帧）
```
⭐⭐⭐ v6 核心修复：低fps场景使用固定队列深度判断升帧

问题分析（v5遗留问题）：
  用水位判断升帧在低fps场景下失效：
  
  场景：60fps → 10fps（网络恶化触发降帧）
    原始：Q_target=18帧，Q=10帧，水位=55%
    降帧后：
      - 到达=10fps
      - EMA随之降到10fps
      - Q_target = 10 × 300 / 1000 = 3帧（QUEUE_MIN）
      - 实际队列稳定在2-3帧
      - 水位 = 2/3 = 66% 或 3/3 = 100%
    
    看似可以触发恢复（>50%），但实际：
      - Q_target=3帧是最小值，不会再降
      - 即使网络好转，队列也只能稳定在3帧（没有更多帧到达）
      - 水位永远在60-100%波动，但队列绝对值很小
      - 无法区分"网络好但低fps"和"网络差"

v6解决方案：低fps场景使用固定队列深度阈值

判断条件：
  1. 当前处于低fps模式（m_requestedFps > 0，已触发降帧）
  2. 实际到达帧率 < 20fps（低fps场景）
  3. 队列深度 >= 固定阈值（如5帧）持续5秒  ⭐ 关键！
  
恢复逻辑：
  if (m_requestedFps > 0) {  // 当前有降帧在生效
      double actualFps = m_arrivalRateEma;
      
      if (actualFps < 20) {
          // ⭐⭐⭐ 低fps场景：用固定队列深度判断
          // 队列稳定在>=5帧 持续5秒，说明网络已好转
          if (queueDepth >= RESTORE_QUEUE_THRESHOLD) {  // 5帧
              m_highWaterHoldSec++;
          } else {
              m_highWaterHoldSec = 0;  // 重置
          }
      } else {
          // 高fps场景：保持原有水位判断逻辑
          if (W > W_REQUEST_RESTORE_FPS) {  // 50%
              m_highWaterHoldSec++;
          } else {
              m_highWaterHoldSec = 0;
          }
      }
      
      if (m_highWaterHoldSec >= FPS_RESTORE_HOLD_SEC) {  // 5秒
          // 触发恢复请求
          emit requestFpsChange(0);
      }
  }

新增常量：
  RESTORE_QUEUE_THRESHOLD = 5帧  // 低fps升帧的固定队列阈值

效果对比：
  | 场景 | v5逻辑 | v6逻辑 |
  |------|--------|--------|
  | 10fps，队列=3帧 | 水位=100%，但队列只有3帧，可能误判 | 队列<5帧，不升帧 |
  | 10fps，队列=5帧 | 水位=166%，触发恢复 | 队列>=5帧，触发恢复 ✓ |
  | 30fps，队列=6帧 | 水位=66%>50%，触发恢复 | 高fps用水位，66%>50%触发 ✓ |
```

旧方案（v5）说明（保留作为高fps场景逻辑）：
```
当水位恢复到>50%并持续5秒，触发恢复请求：
  - 发送 requestFpsChange(0)
  - QML收到0后，使用相机设定中的原始fps值
  - 原始fps来自 iosCameraSettingsPopup.fpsValue（服务器fps格式）
```

### 7.6 日志示例（v6更新）
```
降帧日志：
📉 第二道防线触发 | 水位=25%持续1秒 | 请求前端降帧: 60fps→45fps (服务器:240→180)
📤 已发送FPS到iOS: 服务器fps=180 (iOS除以4后=45fps)

📉 第二道防线触发 | 水位=20%持续1秒 | 请求前端降帧: 45fps→30fps (服务器:180→120)
📤 已发送FPS到iOS: 服务器fps=120 (iOS除以4后=30fps)

升帧日志（高fps场景，用水位判断）：
📈 第二道防线解除 | 水位=65%>50% 持续5秒 | 请求前端恢复帧率: 30fps→60fps (服务器:120→240)

升帧日志（⭐ v6 低fps场景，用固定队列判断）：
📈 第二道防线解除 | 队列=5帧>=阈值5 持续5秒 | 请求前端恢复帧率: 10fps→60fps (服务器:40→240)
```

### 7.7 与其他防线的配合（v6更新）
```
时间线示例（极端网络恶化 + 低fps场景）：

T=0s:   正常播放，实际60fps，服务器fps=240
T=1s:   网络恶化，队列下降，水位<30%
        → 第一道防线：队列扩容 9→12帧
        → 第二道防线：开始计时（水位<30%）
T=2s:   水位<30%持续1秒
        → 第二道防线触发：降帧60→45fps（服务器240→180）
T=3s:   iOS收到降帧指令，推流减少
T=4s:   网络仍差，水位<30%又持续1秒
        → 第二道防线：降帧45→30fps（服务器180→120）
T=5s:   网络继续恶化
        → 第二道防线：降帧30→20fps（服务器120→80）
T=6s:   → 第二道防线：降帧20→15fps（服务器80→60）
T=7s:   → 第二道防线：降帧15→10fps（服务器60→40）← 到达最低
        此时：到达=10fps，Q_target=3帧（QUEUE_MIN）

--- 网络恢复 ---

T=10s:  网络恢复，帧开始正常到达
        到达=10fps，队列开始上涨
T=11s:  队列=3帧（仍然是低fps场景）
        ⭐ v6: 低fps(<20fps)用固定队列判断，而非水位
        队列<5帧阈值，不触发恢复计时
T=12s:  队列=4帧，仍<5帧阈值
T=13s:  队列=5帧，>=5帧阈值，开始计时！
T=14s:  队列=5帧，持续2秒
T=15s:  队列=5帧，持续3秒
T=16s:  队列=5帧，持续4秒
T=17s:  队列=5帧，持续5秒 ⭐
        → 第二道防线解除：恢复60fps（服务器240）
T=18s:  iOS收到升帧指令，推流增加
T=20s:  队列稳定，收缩到正常
        → 恢复完全正常播放

关键点（v6优化）：
  - 紧急保护触发：queueDepth==0（原queueDepth==1）→ 减少低fps时频繁触发
  - 降帧触发：水位<30%持续1秒 → 所有fps场景通用
  - 升帧触发（高fps>=20）：水位>50%持续5秒 → 保持v5逻辑
  - 升帧触发（低fps<20）：队列>=5帧持续5秒 → ⭐ v6新增：用固定阈值判断
  - 追帧速度：最高110%
  - 队列过满：>150%直接丢帧
  - 最低保底10fps（服务器40），保证基本流畅
```

---

## 8. FPS 变化场景分析

### 8.1 场景：60fps → 5fps（极端降低）

```
时间线：
T=0s:   F_config=60fps, Q_target=18帧, 正常播放

T=0.1s: 推流突然变成5fps
        消耗(60fps) >> 到达(5fps) → 队列快速下降

T=1s:   检测到 |5-60|/60 = 92% > 30%
        → fpsChangeCounter = 1

T=3s:   fpsChangeCounter = 3，触发自动重配置！
        → setConfigFps(5)
        → Q_target = 5×300/1000 = 1.5 → clamp = 3帧
        → m_configFps = 5fps

T=4s:   新稳态：
        - Q_target = 3帧
        - 延迟 = 100 + 3×200 = 700ms
```

### 8.2 setConfigFps 执行逻辑
```cpp
void setConfigFps(double fps) {
    fps = clamp(fps, 3.0, 240.0);  // ⭐ 支持低至3fps
    
    // 1. 更新配置
    m_configFps = fps;
    m_arrivalRateEma = fps;
    
    // 2. 重新计算队列目标（保持恒定延迟）
    m_queueTarget = fps * TARGET_DELAY_MS / 1000;
    m_queueTarget = clamp(m_queueTarget, QUEUE_MIN, QUEUE_MAX);
    
    // 3. 重置所有状态
    m_playbackRate = 1.0;
    m_emergencyHold = false;
    m_stableCounter = 0;
}
```

---

## 9. 极值情况处理

### 9.1 网络完全中断（到达=0fps）⭐⭐⭐
```
条件：连续多秒 N_arrive = 0
处理：
  1. ⭐ EMA强制衰减（每秒×0.3，最低3fps）
  2. Q_target 根据衰减后的 EMA 自动降低
  3. 紧急保护激活
  4. Q_target → 3帧（快速恢复准备）
  5. 显示最后有效帧
  6. 等待网络恢复
  7. ⭐ 网络恢复时 EMA 偏差>3x，立即重置
  8. 2帧到达即可解除紧急保护

EMA衰减示例（60fps断开）：
  T=1s: 60 × 0.3 = 18fps
  T=2s: 18 × 0.3 = 5.4fps
  T=3s: 5.4 × 0.3 = 1.6 → clamp(3.0) = 3fps
```

### 9.2 极低帧率（3-10fps）
```
条件：F_push < 10fps
处理：
  1. 允许 m_configFps 低至 3fps
  2. Q_target = max(3, fps×300/1000)
  3. 延迟 = 100 + Q_target×(1000/fps)
  
例：5fps → Q_target=3帧 → 延迟=700ms
```

### 9.3 队列溢出（Q > Q_MAX × 2）
```
条件：Q(t) > 60帧
处理：
  1. 丢弃最旧帧，限制 Q ≤ 90帧
  2. R_play = 1.1（最大追帧速度）⭐ v5: 1.2→1.1
  3. 快速消耗积压
```

### 9.4 ⭐⭐⭐ 队列过满丢帧机制（v5 新增）
```
问题：
  队列积压到144%（如26/18帧）时，靠110%追帧需要3秒才能消化
  用户体验：持续3秒的快放，很突兀、吓人

解决方案：
  当水位 > 150% 时，直接丢弃旧帧到 120%，瞬间完成

触发条件：
  W > W_DROP_THRESHOLD (1.5) && queueDepth > 3

处理逻辑：
  targetDepth = queueTarget × W_DROP_TARGET (1.2)
  framesToDrop = queueDepth - targetDepth
  丢弃最旧的 framesToDrop 帧

效果对比：
  | 场景 | 旧方案 | v5方案 |
  |------|--------|--------|
  | 26/18帧(144%) | 110%追帧3秒 | 不丢帧，温和追帧 |
  | 30/18帧(167%) | 110%追帧5秒 | 丢8帧→22帧，瞬间 |
  | 40/18帧(222%) | 110%追帧10秒 | 丢18帧→22帧，瞬间 |

C++代码：
  // 队列过满时丢帧（避免长时间快放）
  if (W > W_DROP_THRESHOLD && queueDepth > 3) {
      int targetDepth = queueTarget * W_DROP_TARGET;
      targetDepth = qMax(targetDepth, 3);  // 至少保留3帧
      int framesToDrop = queueDepth - targetDepth;
      
      for (int i = 0; i < framesToDrop && queue.size() > 3; i++) {
          GstSample *dropped = queue.takeFirst();
          gst_sample_unref(dropped);
      }
      log("📉 队列过满丢帧 | 丢弃" + framesToDrop + "帧");
  }

日志示例：
  📉 队列过满丢帧 | 丢弃8帧 | 剩余22帧 水位=122%
```

---

## 10. 反馈控制系统框图

```
                    ┌──────────────────────────────────────────────────────┐
                    │                  负反馈控制系统                        │
                    │                                                      │
  F_push ──────────►│  ┌─────────┐      ┌─────────┐      ┌─────────┐      │
  (推流FPS)         │  │ GStream │      │ 帧队列  │      │ 渲染器  │      │
                    │  │ 接收    │─────►│ Q(t)    │─────►│ 消耗    │──────┼──► 显示
  网络波动 ─────────┼─►│ +100ms  │      │         │      │         │      │
                    │  └─────────┘      └────┬────┘      └────┬────┘      │
                    │                        │                │           │
                    │                        ▼                │           │
                    │                   ┌─────────┐           │           │
                    │                   │ W(t)    │           │           │
                    │                   │ 水位    │           │           │
                    │                   └────┬────┘           │           │
                    │                        │                │           │
                    │        ┌───────────────┼───────────────┐│           │
                    │        ▼               ▼               ▼│           │
                    │   ┌─────────┐    ┌──────────┐    ┌─────────┐       │
                    │   │Q_target │    │分段函数  │    │ R_play  │       │
                    │   │队列目标 │    │piecewise │───►│ 播放速率│       │
                    │   │(第一道) │    │  Rate    │    │(第二道) │       │
                    │   └─────────┘    └──────────┘    └─────────┘       │
                    │        │                               │           │
                    │        │     ┌─────────────┐           │           │
                    │        └────►│ T_render    │◄──────────┘           │
                    │              │ 渲染间隔    │                        │
                    │              └──────┬──────┘                        │
                    │                     │                              │
                    │                     ▼                              │
                    │              ┌─────────────┐                        │
                    │              │ 实际消耗速率│────────────────────────┘
                    │              └─────────────┘
                    └──────────────────────────────────────────────────────┘

反馈回路：
1. 水位低 → 扩队列 + 降速(分段函数) → 消耗变慢 → 水位上升 → 稳定
2. 水位高 → 缩队列 + 追帧(分段函数) → 消耗变快 → 水位下降 → 稳定
3. 紧急保护 → Q_target→3帧 → 更容易恢复 → 网络恢复即解除
```

---

## 11. 完整算法伪代码

```cpp
// ========== 每帧执行 ==========
void onRenderTick() {
    Q = queue.size()
    W = Q / Q_target
    
    // ===== 每秒执行策略调整（1Hz决策频率）=====
    if (now - lastCheck >= 1000ms) {
        lastSecondFps = currentSecondFrames
        currentSecondFrames = 0
        
        // ⭐⭐⭐ 关键：强制同步 EMA 与实际到达帧率
        syncEmaWithActualFps()
        
        // FPS变化检测（自动唤醒机制）
        detectFpsChange()
        
        // 第一道防线：队列调整
        adjustQueueTarget(Q)
        
        // 第二道防线：速率调整（分段函数+导数限制）
        adjustPlaybackRate(Q)
    }
    
    // ===== 紧急保护自动解除（每帧检查）=====
    if (E_hold && W > 0.5) {
        E_hold = false
        log("✅ 紧急保护自动解除")
    }
    
    // ===== ⭐ v5: 队列过满丢帧 =====
    if (W > 1.5 && Q > 3) {
        targetDepth = Q_target * 1.2
        framesToDrop = Q - targetDepth
        for (i = 0; i < framesToDrop; i++) {
            queue.dropFirst()  // 丢弃旧帧
        }
        Q = queue.size()
        W = Q / Q_target
        log("📉 队列过满丢帧")
    }
    
    // ===== 取帧决策（v6修复）=====
    if (E_hold || W < 0.15) {
        render(lastValidFrame)  // 紧急：显示最后帧
    } else if (Q > 1) {
        frame = queue.takeFirst()
        lastValidFrame = frame
        render(frame)
    } else if (Q == 1) {
        // ⭐ v6修复：取最后1帧时不触发紧急保护
        frame = queue.takeFirst()
        lastValidFrame = frame
        render(frame)
        // 不触发 E_hold，让帧正常消耗
    } else if (Q == 0) {
        // ⭐ v6修复：只有队列真正为空时才触发紧急保护
        E_hold = true
        render(lastValidFrame)
    }
    
    // ===== 计算下一帧间隔 =====
    T_next = smooth(1000 / F_ema / R_play)
    timer.setInterval(T_next)
}

// ========== ⭐⭐⭐ EMA强制同步（关键修复）==========
void syncEmaWithActualFps() {
    if (lastSecondFps == 0) {
        // 网络断开：快速衰减 EMA
        F_ema = max(3.0, F_ema * 0.3)  // 每秒衰减70%
    } else {
        double ratio = lastSecondFps / F_ema
        if (ratio < 0.3 || ratio > 3.0) {
            // 偏差超过3倍：立即重置
            F_ema = lastSecondFps
        }
    }
}

// ========== 第一道防线：队列扩缩 ==========
void adjustQueueTarget(Q) {
    // ⭐⭐⭐ 使用实际到达帧率（EMA）而非配置帧率
    actualFps = F_ema > 3 ? F_ema : F_config
    
    // ⭐⭐⭐ 基于实际fps计算队列上限（防止低fps时盲目扩容）
    fpsBasedMax = clamp(actualFps * 500 / 1000, Q_MIN, Q_MAX)
    
    W = Q / Q_target
    
    // ⭐⭐⭐ FPS突变时快速调整
    idealTarget = actualFps * appDelayMs / 1000
    if (abs(idealTarget - Q_target) > 3) {
        Q_target += (idealTarget - Q_target) / 2
        Q_target = clamp(Q_target, Q_MIN, fpsBasedMax)
        return
    }
    
    if (W < 0.35 && Q_target < fpsBasedMax) {
        Q_target += 3  // 快速扩容（但不超过fps上限）
        stableCounter /= 2
    } else if (W > 0.8 && Q_target > Q_MIN) {
        stableCounter++
        if (stableCounter >= 60) {
            Q_target -= 1  // 缓慢收缩
            stableCounter = 0
        }
    } else if (W < 0.15) {
        E_hold = true
        Q_target = Q_MIN  // ⭐ 关键：紧急时降低队列目标
    }
}

// ========== 第二道防线：速率调整 ==========
void adjustPlaybackRate(Q) {
    W = Q / Q_target
    
    // 分段函数计算目标速率
    R_target = piecewiseRate(W)
    
    // 导数限制（平滑过渡）
    delta = clamp(R_target - R_play, -0.05, +0.05)
    R_play = clamp(R_play + delta, 0.7, 1.1)  // ⭐ v5: 1.2→1.1
}

// ========== 分段函数（v5更新）==========
double piecewiseRate(W) {
    if (W < 0.15) return 0.7;
    if (W < 0.35) return 0.7 + 0.3 * (W - 0.15) / 0.2;
    if (W < 1.05) return 1.0;
    if (W < 1.50) return 1.0 + 0.1 * (W - 1.05) / 0.45;  // ⭐ v5: 0.2→0.1
    return 1.1;  // ⭐ v5: 1.2→1.1
}
```

---

## 12. 关键设计点总结

| 问题 | 解决方案 |
|------|----------|
| **30fps和60fps延迟不一致** | 队列帧数 = TARGET_DELAY × FPS / 1000，保持恒定400ms |
| **紧急保护无法恢复** | ⭐ 激活时 Q_target→3帧，2帧即可解除 |
| **低fps紧急保护频繁触发/解除** | ⭐⭐⭐ v6: 只有 queueDepth==0 才触发（原 queueDepth==1）|
| **低fps无法触发升帧** | ⭐⭐⭐ v6: 低fps(<20)用固定队列阈值(≥5帧)判断，而非水位 |
| **网络断开后FPS无法恢复** | ⭐⭐⭐ EMA强制衰减（每秒×0.3）+ 偏差>3x立即重置 |
| **极端网络队列振荡卡顿** | ⭐⭐⭐ v2: 增加缓冲(5→10ms/fps) + 阈值(3→6帧) + 冷却3秒 |
| **极端网络本地调节不足** | ⭐⭐⭐ v3: 第二道防线，通知iOS降低推流帧率（60→45→30→20→15→10） |
| **第二道防线触发太晚** | ⭐⭐⭐ v4: 更早触发（水位<30%持续1秒，原20%+2秒）|
| **第二道防线无法恢复(高fps)** | ⭐⭐⭐ v5: 恢复阈值降低（水位>50%持续5秒，原80%+3秒）|
| **追帧太快用户吓人** | ⭐⭐⭐ v5: 追帧速度 1.2→1.1，更温和 |
| **队列过满长时间快放** | ⭐⭐⭐ v5: 水位>150%直接丢帧到120%，瞬间完成 |
| **FPS换算错误** | ⭐⭐⭐ v3: 服务器fps = 实际fps × 4，iOS收到后除以4 |
| **低fps时队列盲目扩容** | 基于实际fps计算队列上限（最多600ms延迟） |
| **极低fps不支持** | 最小支持 3fps，移除 10fps 下限 |
| FPS突变导致画面静止 | 自动检测FPS变化，3秒后自动重配置 |
| 速度跳变 | 导数限制：每帧±5%，分段函数平滑 |
| 队列抖动 | 扩容快(+3帧)，收缩慢(-1帧/3秒) |
| 追帧过快导致卡顿 | 线性追帧，最高1.2倍速 |
| 无推流时误触发第二道防线 | ⭐ v3: 检查m_lastSecondFps>0才触发 |

---

## 13. 测试场景验证

### 场景A：稳定30fps
```
输入：F_push=30fps 稳定
期望：Q_target = 300×30/1000 = 9帧
      Q ≈ 9帧, W ≈ 1.0, R_play = 1.0
延迟：100 + 300 = 400ms ✓
```

### 场景B：稳定60fps
```
输入：F_push=60fps 稳定
期望：Q_target = 300×60/1000 = 18帧
      Q ≈ 18帧, W ≈ 1.0, R_play = 1.0
延迟：100 + 300 = 400ms ✓ （与30fps一致！）
```

### 场景C：极低fps（5fps）
```
输入：F_push=5fps 稳定
期望：Q_target = max(3, 300×5/1000) = 3帧
      Q ≈ 3帧, W ≈ 1.0, R_play = 1.0
延迟：100 + 600 = 700ms ✓
```

### 场景D：网络完全中断后恢复 ⭐⭐⭐（EMA同步修复）
```
T=0s:  正常60fps，EMA=60，Q_target=18帧
T=1s:  网络中断，到达=0fps
       → EMA = 60 × 0.3 = 18fps（强制衰减）
       → Q_target 根据 EMA=18 计算 → 约5帧
T=2s:  仍断开，到达=0fps
       → EMA = 18 × 0.3 = 5.4fps
       → Q_target 根据 EMA=5 计算 → 3帧
T=3s:  → EMA = 5.4 × 0.3 = 1.6 → clamp(3.0) = 3fps
       → 队列耗尽，紧急保护激活
       → Q_target = 3帧，EMA = 3fps
T=5s:  网络恢复，到达=20fps
       → ratio = 20/3 = 6.7 > 3.0 → EMA立即重置为20fps
       → 队列=3帧，W=100%，自动解除紧急保护
       → Q_target 根据 EMA=20 快速调整
T=6s:  到达=30fps，EMA平滑更新
       → 恢复正常播放

关键修复效果：
  - 旧版本：EMA冻结在60fps，网络恢复后Q_target=18帧，恢复缓慢
  - 新版本：EMA快速衰减到3fps，网络恢复后立即重置，快速恢复
```

---

## 14. 日志说明

日志输出到 `yh.txt`，格式：
```
📊 状态[✅正常] | 到达=30fps 配置=30fps | 队列=9/9帧 水位=100% | 速度=100% | 延迟≈400ms
📊 状态[⬆️恢复] | 到达=15fps 配置=30fps | 队列=3/12帧 水位=25%  | 速度=85%  | 延迟≈500ms
📊 状态[🛑紧急] | 到达=0fps  配置=30fps | 队列=1/3帧  水位=33%  | 速度=70%  | 延迟≈133ms
📊 状态[🚀追帧] | 到达=35fps 配置=30fps | 队列=12/9帧 水位=133% | 速度=107% | 延迟≈350ms  ⭐ v5: 最高110%
📉 队列过满丢帧 | 丢弃8帧 | 剩余22帧 水位=122%  ⭐ v5: 水位>150%时丢帧
```

状态图标：
- 🛑 紧急 (W < 0.15，停止消耗)
- ⬆️ 恢复 (0.15 ≤ W < 0.35)
- ✅ 正常 (0.35 ≤ W < 1.05)
- 🚀 追帧 (W ≥ 1.05)

---

## 15. Java与C++版本对比分析（v7 优化方向）

### 15.1 发现：Java版本没有应用层队列也能实现低延迟稳定播放

通过对比 `SimpleWebRTCPlayer.java` 和 `gstplayer.cpp`，发现Java版本：
- **无应用层队列**：不维护 `m_frameQueue`，直接依赖GStreamer内部队列
- **延迟更低**：约300-400ms（vs C++的300-800ms）
- **同样稳定**：画面不卡顿、不马赛克

### 15.2 配置对比表

| 配置项 | Java版本 | C++版本 | 差异影响 |
|--------|----------|---------|----------|
| **jitterbuffer latency** | 300-400ms | 200ms | Java更大但总延迟更低 |
| **drop-on-latency** | false | false | 都关闭主动丢包 |
| **queueDepay** | 22-28帧/400-500ms | 30帧/600ms | Java用帧数+时间双限制 |
| **queueDecode** | 16-25帧 | 25帧 | 基本一致 |
| **displayQueue** | 2-5帧, leaky=2 | 3-5帧, leaky=2 | 基本一致 |
| **appsink max-buffers** | 30 | 1 | ⭐ Java内部缓冲更大 |
| **appsink drop** | true | true | 都丢弃老帧 |
| **应用层队列** | 无 | 3-36帧动态 | ⭐ C++额外一层缓冲 |
| **总延迟** | ≈300-400ms | 200+100-600ms | C++可能高达800ms |

### 15.3 Java版本详细配置（SimpleWebRTCPlayer.java）

```java
// ========== jitterbuffer 配置 ==========
// 按机型/分辨率动态调整（300-400ms）
elem.set("latency", jitterLatencyMs);           // 300-400ms
elem.set("do-retransmission", true);            // 启用重传
elem.set("rtx-max-retries", retryCount);        // 15次
elem.set("rtx-retry-timeout", retryTimeoutMs);  // 20-30ms
elem.set("drop-on-latency", false);             // ⭐ 关闭主动丢包！

// ========== queueDepay 配置（RTP解封装后）==========
queueDepay.set("max-size-buffers", 22-28);      // 帧数限制
queueDepay.set("max-size-time", 400-500ms);     // 时间限制
queueDepay.set("leaky", 2);                     // 丢弃老帧

// ========== queueDecode 配置（解码后）==========
queueDecode.set("max-size-buffers", 16-25);
queueDecode.set("leaky", 2);
queueDecode.set("qos", false);                  // 禁用QoS防丢帧

// ========== displayQueue 配置（显示前）==========
displayQueue.set("max-size-buffers", 2-5);      // 极低延迟
displayQueue.set("leaky", 2);

// ========== appsink 配置 ==========
appsink.set("emit-signals", true);
appsink.set("sync", false);                     // 不与时钟同步
appsink.set("async", false);
appsink.set("max-buffers", 30);                 // ⭐ 内部缓冲30帧
appsink.set("drop", true);                      // 丢弃老帧

// ⭐ 无应用层队列！直接渲染appsink拉到的帧
```

### 15.4 C++版本当前配置（gstplayer.cpp）

```cpp
// ========== jitterbuffer 配置 ==========
int jitterLatencyMs = 200;                      // 固定200ms（比Java小）
elem.set("latency", jitterLatencyMs);
elem.set("drop-on-latency", false);
elem.set("do-retransmission", true);
elem.set("rtx-max-retries", 15);
elem.set("rtx-retry-timeout", 25);

// ========== queueDepay 配置 ==========
queueDepay.set("max-size-buffers", 30);
queueDepay.set("max-size-time", 600ms);
queueDepay.set("leaky", 2);

// ========== appsink 配置 ==========
appsink.set("sync", false);
appsink.set("max-buffers", 1);                  // ⭐ 只缓冲1帧！
appsink.set("drop", true);

// ========== 应用层队列（额外的！）==========
m_frameQueue: QList<GstSample*>                 // 3-36帧动态
m_queueTarget: 动态计算 = FPS × DELAY_PER_FPS / 1000
总延迟 = GST_JITTER_LATENCY(100ms) + 应用层延迟(100-600ms)
```

### 15.5 为什么Java版本延迟低但效果好？

#### 1. 单层缓冲 vs 双层缓冲
```
Java版本（单层）：
  webrtcbin → jitterbuffer(300ms) → queue → decoder → queue → appsink(30帧) → 显示
  总缓冲：≈300-400ms

C++版本（双层）：
  webrtcbin → jitterbuffer(200ms) → queue → decoder → queue → appsink(1帧) → [应用层队列(3-36帧)] → 显示
  总缓冲：200ms + 100-600ms = 300-800ms
```

#### 2. appsink max-buffers 的作用
```
Java: max-buffers=30
  - GStreamer内部管理30帧的环形缓冲
  - 网络抖动时，帧在内部队列排队
  - 应用层直接取最新帧渲染，无需自己维护队列

C++: max-buffers=1
  - GStreamer只保留1帧
  - 必须在应用层维护队列来应对网络抖动
  - 导致双重缓冲，延迟叠加
```

#### 3. leaky=2 的统一使用
```
所有queue都使用leaky=2（丢弃老帧）：
  - 网络拥塞时自动丢弃老帧
  - 延迟不会无限累积
  - Java和C++都这样配置（正确）
```

### 15.6 v7 优化方向：简化为单层缓冲

#### 方案A：提高jitterbuffer延迟 + 去掉应用层队列
```cpp
// 优化后的配置
int jitterLatencyMs = 350;                      // 200 → 350ms
appsink.set("max-buffers", 30);                 // 1 → 30

// 去掉 m_frameQueue
// 直接在 appsink new-sample 回调中渲染

// 预期效果：
// - 总延迟：≈350-400ms（与Java一致）
// - 代码简化：移除复杂的应用层队列管理
// - 维护成本降低
```

#### 方案B：保留应用层队列但缩小延迟
```cpp
// 如果需要保留应用层队列的精细控制
int jitterLatencyMs = 200;                      // 保持200ms
DELAY_PER_FPS = 5;                              // 10 → 5（减半）
DELAY_MAX = 200;                                // 600 → 200ms
appsink.set("max-buffers", 10);                 // 1 → 10

// 预期效果：
// - 总延迟：200 + 50-200ms = 250-400ms
// - 保留自适应播放控制能力
```

### 15.7 配置参数建议（v7）

```cpp
// ========== GStreamer层（对齐Java）==========
static constexpr int GST_JITTER_LATENCY = 350;  // 100 → 350ms（增大）

// jitterbuffer
elem.set("latency", 350);                       // 200 → 350ms
elem.set("drop-on-latency", false);             // 保持关闭

// queueDepay（减小）
queueDepay.set("max-size-buffers", 22);         // 30 → 22
queueDepay.set("max-size-time", 400_000_000);   // 600ms → 400ms

// appsink（增大内部缓冲）
appsink.set("max-buffers", 30);                 // 1 → 30
appsink.set("drop", true);                      // 保持丢弃老帧

// ========== 应用层（v8.3延迟阈值调整）==========
// v8.3：最低延迟从80ms提升到150ms，其他顺延+70ms
BUFFER_RATIO_MIN = 0.15;                        // 0.08 → 0.15（150ms延迟）
BUFFER_RATIO_OPTIMAL = 0.22;                    // 0.15 → 0.22（220ms延迟）
BUFFER_RATIO_MAX = 0.47;                        // 0.40 → 0.47（470ms延迟）
APP_DELAY_LOWER_START = 270;                    // 200 → 270ms
APP_DELAY_LOWER_MAX = 470;                      // 400 → 470ms
APP_DELAY_RESTORE = 220;                        // 150 → 220ms
DELAY_MIN = 150;                                // 80 → 150ms
```

### 15.8 延迟对比预期

| 场景 | C++当前(v6) | Java版本 | C++优化后(v7) |
|------|-------------|----------|---------------|
| 30fps稳定 | 200+300=500ms | 350ms | 350-400ms |
| 60fps稳定 | 200+600=800ms | 400ms | 350-400ms |
| 10fps稳定 | 200+100=300ms | 350ms | 350ms |
| 网络抖动 | 自适应扩容 | GStreamer内部处理 | GStreamer+小幅扩容 |

### 15.9 关键结论

1. **Java版本证明**：GStreamer内部的jitterbuffer+queue足以应对网络抖动
2. **应用层队列的代价**：增加延迟、增加代码复杂度
3. **优化核心**：
   - 增大jitterbuffer延迟（200→350ms）
   - 增大appsink max-buffers（1→30）
   - 简化或移除应用层队列
4. **保留第二道防线**：推流帧率控制（降帧/升帧）仍然有价值

### 15.10 v7 实施步骤

```
Phase 1: 配置调优（不改架构）✅ 已完成
  1. ✅ jitterbuffer latency: 200 → 350ms (gstplayer.cpp)
  2. ✅ appsink max-buffers: 1 → 30 (gstplayer.cpp)
  3. ✅ GST_JITTER_LATENCY: 100 → 350 (gstplayer.h)
  4. ✅ DELAY_PER_FPS: 10 → 5 (gstplayer.h)
  5. ✅ DELAY_MAX: 600 → 200 (gstplayer.h)
  6. ✅ QUEUE_MAX: 36 → 12 (gstplayer.h)
  
Phase 2: 观察效果（待测试）
  1. 对比延迟：目标降到350-550ms（vs 旧版500-800ms）
  2. 观察稳定性：确保不增加卡顿
  3. 收集日志：分析jitterbuffer/queue行为
  4. 对比Java版本延迟（目标300-400ms）

Phase 3: 架构简化（可选，根据Phase 2结果决定）
  1. 如果Phase 1效果好，考虑完全移除应用层队列
  2. 保留紧急保护逻辑（渲染最后有效帧）
  3. 保留第二道防线（推流帧率控制）
```

### 15.11 v7 修改摘要

| 文件 | 修改项 | 旧值 | 新值 |
|------|--------|------|------|
| gstplayer.h | DELAY_PER_FPS | 10ms | 5ms |
| gstplayer.h | DELAY_MAX | 600ms | 200ms |
| gstplayer.h | QUEUE_MAX | 36帧 | 12帧 |
| gstplayer.h | GST_JITTER_LATENCY | 100ms | 350ms |
| gstplayer.cpp | jitterLatencyMs | 200ms | 350ms |
| gstplayer.cpp | appsink max-buffers | 1 | 30 |

**预期延迟变化**：
- 30fps场景：旧500ms → 新450-500ms
- 60fps场景：旧800ms → 新500-550ms
- 低fps场景：基本不变（100ms应用层最小延迟）

### 15.12 v8 客户方案

**核心思路**：以当前设置的推流帧率为中心参与计算

#### 两道保险体系

**第一道保险**：通过播放速度控制延迟（v8.3调整阈值）
- 最佳缓冲 = 帧率 × 22%（对应220ms延迟）⭐v8.3调整
- 队列下限 = 帧率 × 15%（对应150ms延迟）⭐v8.3调整
- 队列上限 = 帧率 × 47%（对应470ms延迟）⭐v8.3调整
- 队列多 → 网速好 → 适当加速（减少延迟）
- 队列少 → 网速差 → 降低速度（增加延迟）

**第二道保险**：以延迟控制推流帧率（见 v8.1 优化）

#### 不同帧率下的参数计算（v8.3调整）

| 帧率 | 最低缓冲(150ms) | 最佳缓冲(220ms) | 最大缓冲(470ms) |
|------|-----------------|-----------------|-----------------|
| 60fps | 9帧 | 13帧 | 28帧 |
| 30fps | 4.5帧 | 6.6帧 | 14帧 |
| 10fps | 1.5帧 | 2.2帧 | 4.7帧 |

---

### 15.13 v8.1 客户优化方案（⭐⭐⭐ 最新）

**核心改动**：
1. **仅应用层延迟**判断（不含 jitterbuffer 350ms）
2. **平滑降帧**（线性插值，非阶梯式）
3. **升帧限制**：不能超过到达帧率

#### 常量定义（gstplayer.h）
```cpp
// ⭐⭐⭐ 延迟阈值（仅应用层延迟，不含 jitterbuffer）- v8.1调整
APP_DELAY_LOWER_START = 80ms   // 应用层延迟 > 80ms 开始降帧⭐v8.1调整
APP_DELAY_LOWER_MAX = 280ms    // 应用层延迟达到 280ms 时降到最低帧率⭐v8.1调整
// 升帧条件：应用延迟在 80ms-280ms 之间（网络已恢复，可以逐步升帧）

// ⭐⭐⭐ 帧率范围（客户优化）
FPS_PUSH_MAX = 60fps           // 推流帧率上限
FPS_PUSH_MIN = 15fps           // ⭐ 10→15：推流帧率下限
FPS_RESTORE_STEP = 10%         // ⭐ 每次升帧幅度 = 到达帧率 × 10%

// ⭐⭐⭐ 降帧触发条件
WATER_LEVEL_HIGH = 35%         // 水位<35%开始降帧
WATER_LEVEL_LOW = 5%           // 水位<5%降到最低帧率
FPS_WATER_MIN = 20fps          // 水位触发的最低帧率20fps（服务器80fps）
FPS_DELAY_MIN = 15fps          // 延迟触发的最低帧率15fps（服务器60fps）

// 触发时间
FPS_LOWER_HOLD_SEC = 1秒       // 持续1秒触发降帧
FPS_RESTORE_HOLD_SEC = 1秒     // ⭐ 3→1秒：持续1秒触发升帧
```

#### 降帧逻辑：双条件平滑降帧

```
⭐⭐⭐ 两个独立的降帧条件，取更低的帧率：

条件1（水位触发）：水位 35%→5% 对应 60fps→20fps
条件2（延迟触发）：延迟 >320ms 对应 60fps→15fps（v8.3调整）

最终帧率 = min(水位计算帧率, 延迟计算帧率)
```

**条件1：水位触发（35%→5% = 60fps→20fps）**
```
┌─────────────┬────────────┬─────────────┐
│ 水位        │ 实际帧率   │ 服务器帧率  │
├─────────────┼────────────┼─────────────┤
│ ≥35%        │ 不降帧     │ -           │
│ 35%         │ 60fps      │ 240fps      │
│ 25%         │ 47fps      │ 188fps      │
│ 15%         │ 33fps      │ 132fps      │
│ 5%          │ 20fps      │ 80fps       │
└─────────────┴────────────┴─────────────┘

公式：targetFps = 60 - 40 × (0.35 - 水位) / 0.30
```

**条件2：延迟触发（>80ms = 60fps→15fps）⭐v8.1调整**
```
┌─────────────┬────────────┬─────────────┐
│ 应用延迟    │ 实际帧率   │ 服务器帧率  │
├─────────────┼────────────┼─────────────┤
│ ≤80ms       │ 不降帧     │ -           │
│ 80ms        │ 60fps      │ 240fps      │
│ 180ms       │ 37fps      │ 148fps      │
│ 280ms       │ 15fps      │ 60fps       │
└─────────────┴────────────┴─────────────┘

公式：targetFps = 60 - 45 × (延迟ms - 80) / 200
```

代码实现：
```cpp
// 条件1：水位触发（35%→5% = 60fps→20fps）
if (waterLevel < 0.35) {
    double waterRatio = (0.35 - waterLevel) / (0.35 - 0.05);
    waterRatio = qBound(0.0, waterRatio, 1.0);
    int waterTargetFps = 60 - (60 - 20) * waterRatio;
}

// 条件2：延迟触发（>80ms = 60fps→15fps）⭐v8.1调整
if (appDelayMs > 80) {
    double delayRatio = (appDelayMs - 80) / (280 - 80);
    delayRatio = qBound(0.0, delayRatio, 1.0);
    int delayTargetFps = 60 - (60 - 15) * delayRatio;
}

// 取两个条件中更低的帧率
int targetFps = min(waterTargetFps, delayTargetFps);
```

#### 升帧逻辑：延迟为主 + 到达帧率为辅（⭐客户优化）

```
条件1：应用层延迟 80ms-280ms 之间（网络已恢复）⭐v8.1调整
条件2：持续 1 秒
条件3：每次升帧幅度 ≤ 到达帧率 × 10%（逐步恢复，不激进）⭐
条件4：不能超过到达帧率，不能超过原始帧率

公式：
  maxStepFps = arrivalFps × 10%
  targetFps = currentFps + maxStepFps
  targetFps = min(targetFps, arrivalFps, originalFps)
```

代码实现：
```cpp
// ⭐⭐⭐ 客户反馈：每次升帧幅度不超过到达帧率的10%
int maxStepFps = qMax(1, static_cast<int>(arrivalFps * 0.10));
int targetRestoreFps = m_requestedFps + maxStepFps;

// 限制：不超过到达帧率，不超过原始帧率
int maxAllowedFps = qMin(originalActualFps, static_cast<int>(arrivalFps));
targetRestoreFps = qMin(targetRestoreFps, maxAllowedFps);
targetRestoreFps = qBound(15, targetRestoreFps, 60);

if (targetRestoreFps > m_requestedFps) {
    if (targetRestoreFps >= originalActualFps) {
        // 完全恢复到原始帧率
        emit requestFpsChange(0);
    } else {
        // 逐步恢复（每次最多+10%到达帧率）
        emit requestFpsChange(targetRestoreFps);
    }
}
```

**升帧示例**（到达帧率50fps，原始60fps）：
```
第1次升帧：15fps + 5fps(50×10%) = 20fps
第2次升帧：20fps + 5fps = 25fps
第3次升帧：25fps + 5fps = 30fps
...
最终：恢复到50fps（不超过到达帧率）
```

#### 延迟计算说明（v8.3调整）

```
应用层延迟 = 队列帧数 × 1000 / fps（单位：ms）
总延迟 = 应用层延迟 + jitterbuffer延迟(350ms)

示例（60fps，队列13帧@v8.3最佳缓冲）：
- 应用层延迟 = 13 × 1000 / 60 = 220ms ⭐v8.3调整
- 总延迟 = 220 + 350 = 570ms

⚠️ 重要：降帧/升帧判断仅使用【应用层延迟】，不含 jitterbuffer 延迟！
```

#### 日志示例（v8.3调整）

```
📉 降帧 | 水位=30%(35%→5%) | 60fps→47fps (服务器:240→188) | 到达=50fps 水位=30%
📉 降帧 | 水位=10%(35%→5%) | 47fps→27fps (服务器:188→108) | 到达=30fps 水位=10%
📉 降帧 | 延迟=350ms>270ms | 60fps→42fps (服务器:240→168) | 到达=40fps 水位=50%
📉 降帧 | 延迟=470ms>270ms | 42fps→15fps (服务器:168→60) | 到达=20fps 水位=20%
📈 升帧 | 应用延迟=350ms(270-470ms) 持续1秒 | 15fps→20fps(+5) | 到达=50fps 原始=60fps
📈 升帧 | 应用延迟=320ms(270-470ms) 持续1秒 | 20fps→25fps(+5) | 到达=50fps 原始=60fps
📈 升帧 | 应用延迟=290ms(270-470ms) 持续1秒 | 45fps→50fps(+5) | 到达=50fps 原始=60fps
```

#### 完整流程图

```
                    ┌─────────────────────────────────────┐
                    │          每秒检测一次               │
                    └─────────────────┬───────────────────┘
                                      │
                    ┌─────────────────▼───────────────────┐
                    │ 计算应用层延迟 = 队列帧数×1000/fps  │
                    └─────────────────┬───────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
    ┌─────────▼─────────┐   ┌────────▼────────┐   ┌──────────▼──────────┐
    │ 水位<35%          │   │ 270ms ≤ 延迟    │   │ 水位≥35%且延迟<270ms│
    │ 或 延迟>270ms     │   │ ≤ 470ms 且已降帧│   │ (正常) ⭐v8.3调整   │
    └─────────┬─────────┘   └────────┬────────┘   └──────────┬──────────┘
              │                       │                       │
    ┌─────────▼─────────┐   ┌────────▼────────┐   ┌──────────▼──────────┐
    │ 持续 1 秒后       │   │ 持续 1 秒后     │   │ 重置计数器          │
    │ 双条件平滑降帧    │   │ 逐步升帧        │   │                     │
    │ 水位:60→20fps     │   │ +10%当前帧率    │   │                     │
    │ 延迟:60→15fps     │   │                 │   │                     │
    └─────────┬─────────┘   └────────┬────────┘   └─────────────────────┘
              │                       │
              │             ┌────────▼────────┐
              │             │ 升帧限制:       │
              │             │ ① 每次+10%     │
              │             │ ② ≤到达帧率    │
              │             │ ③ ≤原始帧率    │
              │             └────────┬────────┘
              │                       │
              └───────────────────────┴───────────────────────┐
                                                              │
                                      ┌───────────────────────▼───────────┐
                                      │ 发送 requestFpsChange 信号        │
                                      │ → 通知前端/iOS调整推流帧率        │
                                      └───────────────────────────────────┘
```

#### v8.1 与旧版对比

| 特性 | 旧版 | v8.1 客户优化 |
|------|------|---------------|
| 判断依据 | 总延迟（含GST 350ms） | **仅应用层延迟** |
| 降帧条件1 | - | **水位35%→5% = 60→20fps** ⭐ |
| 降帧条件2 | 延迟>250ms | **延迟>200ms = 60→15fps** |
| 降帧方式 | 阶梯式（60→45→30→20→15→10） | **平滑线性** |
| 升帧触发 | 延迟<150ms | **延迟200-400ms** |
| 升帧等待 | 5秒 | **1秒** |
| 升帧幅度 | 一次性恢复 | **每次最多+10%到达帧率** |
| 升帧上限 | 到达帧率 | **min(到达帧率, 原始帧率)** |

#### 帧率映射关系（重要）

```
实际帧率 × 4 = 服务器帧率 = WebSocket发送值 = UI显示值

┌─────────────┬─────────────┐
│ 实际帧率    │ 服务器帧率  │
├─────────────┼─────────────┤
│ 60fps       │ 240fps      │
│ 45fps       │ 180fps      │
│ 30fps       │ 120fps      │
│ 20fps       │ 80fps       │
│ 15fps       │ 60fps       │
└─────────────┴─────────────┘
```

---

### 15.14 v8.2 动态阈值 + 预测式降帧（⭐⭐⭐ 最新）

**核心改进**：
1. **动态阈值**：降帧/升帧阈值根据当前fps动态计算，低帧率也能正常触发
2. **预测式降帧**：监测队列下降速度，提前介入，不等队列见底

#### 常量定义（gstplayer.h）
```cpp
// 动态阈值比例（基于最佳缓冲）
QUEUE_LOWER_RATIO = 0.35      // 降帧阈值 = 最佳缓冲 × 35%
QUEUE_UPPER_RATIO = 0.70      // 升帧阈值 = 最佳缓冲 × 70%

// 预测式降帧：队列下降速度阈值
DROP_SPEED_WARNING = 3        // 下降≥3帧/秒：预警
DROP_SPEED_CRITICAL = 5       // 下降≥5帧/秒：立即降帧

// 帧率范围
FPS_PUSH_MAX = 60             // 推流帧率上限
FPS_DELAY_MIN = 15            // 延迟触发最低帧率
FPS_QUEUE_MIN = 20            // 队列触发最低帧率
```

#### 动态阈值计算

```
最佳缓冲 = fps × 15%（150ms延迟）
降帧阈值 = 最佳缓冲 × 35%
升帧阈值 = 最佳缓冲 × 70%

┌─────────┬──────────┬──────────┬──────────┬──────────────┐
│ 推流fps │ 最佳缓冲 │ 降帧阈值 │ 升帧阈值 │ 正常波动范围 │
├─────────┼──────────┼──────────┼──────────┼──────────────┤
│ 60fps   │ 9帧      │ <3帧     │ ≥6帧     │ 3-9帧        │
│ 45fps   │ 6.75帧   │ <2帧     │ ≥5帧     │ 2-7帧        │
│ 30fps   │ 4.5帧    │ <2帧     │ ≥3帧     │ 2-5帧        │
│ 20fps   │ 3帧      │ <1帧     │ ≥2帧     │ 1-3帧        │
│ 15fps   │ 2.25帧   │ <1帧     │ ≥2帧     │ 1-2帧        │
└─────────┴──────────┴──────────┴──────────┴──────────────┘
```

#### 降帧条件（满足任一即可）

| 条件 | 判断依据 | 帧率映射 | 说明 |
|------|----------|----------|------|
| 条件1 | 队列 < 动态阈值 | 60fps→20fps | 队列不足 |
| 条件2 | 延迟 > 200ms | 60fps→15fps | 延迟过高 |
| **条件3** | **下降速度 ≥ 3帧/秒** | **提前降帧** | **预测式⭐** |

#### 升帧条件（需同时满足）

| 条件 | 判断依据 | 说明 |
|------|----------|------|
| 条件1 | 队列 ≥ 动态阈值（70%） | 队列充足 |
| 条件2 | 延迟 200-400ms | 延迟正常 |
| **条件3** | **下降速度 ≤ 0** | **队列稳定或增加⭐** |
| 条件4 | 持续1秒 | 防止抖动 |

#### 预测式降帧示例

```
时间  队列  下降速度  动作
T+0   9帧   -        正常
T+1   8帧   1帧/秒   正常波动
T+2   6帧   2帧/秒   预警
T+3   3帧   3帧/秒   ⭐ 提前降帧！（而不是等到0帧）
```

#### 代码实现
```cpp
// 动态阈值计算
int queueOptimal = fps * 0.15;                           // 最佳缓冲
int lowerThreshold = max(1, queueOptimal * 0.35);        // 降帧阈值
int upperThreshold = max(2, queueOptimal * 0.70);        // 升帧阈值

// 预测式：计算队列下降速度
int dropSpeed = m_lastQueueDepth - queueDepth;           // 正值=下降
m_lastQueueDepth = queueDepth;

// 降帧条件（满足任一即可）
bool queueTrigger = (queueDepth < lowerThreshold);       // 队列不足
bool delayTrigger = (appDelayMs > 200);                  // 延迟过高
bool speedTrigger = (dropSpeed >= 3);                    // 下降过快
if (queueTrigger || delayTrigger || speedTrigger) {
    // 触发降帧
}

// 升帧条件（需同时满足）
bool canRestore = (queueDepth >= upperThreshold)         // 队列充足
               && (appDelayMs >= 200 && appDelayMs <= 400)  // 延迟正常
               && (dropSpeed <= 0)                       // 队列稳定
               && (m_requestedFps > 0);                  // 当前已降帧
if (canRestore) {
    // 触发升帧
}
```

#### 日志示例
```
📉 降帧 | 队列=2帧<3帧 | 60fps→47fps (服务器:240→188) | 队列=2帧(阈值3) 延迟=33ms 下降=1帧/秒
📉 降帧 | 下降速度=4帧/秒≥3 | 60fps→40fps (服务器:240→160) | 队列=5帧(阈值3) 延迟=83ms 下降=4帧/秒
📈 升帧 | 队列=7帧≥6帧 延迟=233ms 下降=-1帧/秒 | 40fps→45fps(+5) | 到达=50fps
```

#### v8.2 vs v8.1 对比

| 特性 | v8.1 | v8.2 |
|------|------|------|
| 阈值类型 | 固定（3帧/5帧） | **动态**（基于fps计算） |
| 预测式 | 无 | **监测下降速度，提前介入** |
| 低帧率支持 | 有问题（阈值不合理） | **完全支持** |
| 升帧条件 | 仅延迟判断 | **延迟+队列+下降速度** |

---

### 15.15 v8.3 预测式 - 帧间隔抖动检测（⭐⭐⭐ 当前版本）

**核心改进**：通过监测帧间隔抖动，**提前0.5-1秒感知网络波动**，比传统队列/延迟方式更早响应！

#### 传统方式 vs 预测式

```
❌ 传统方式（响应式，已弃用）：
   网络波动 → 队列下降 → 队列见底 → 触发降帧（太晚了！）

✅ 预测式（v8.3采用）：
   网络波动 → 帧间隔抖动增大 → 抖动>30% → 提前降帧（提前感知！）
```

#### 帧间隔抖动数学公式

```
期望间隔：I_exp = 1000 / fps（如60fps→16.7ms）
实际间隔：I_act = T_当前帧 - T_上一帧
瞬时抖动：J = |I_act - I_exp|
抖动EMA：J_ema = α × J + (1-α) × J_ema   （α=0.3）
抖动比例：R = J_ema / I_exp
```

#### 常量定义（gstplayer.h）

```cpp
// 帧间隔抖动检测
static constexpr double JITTER_ALPHA = 0.3;         // 抖动EMA系数
static constexpr double JITTER_LOWER_RATIO = 0.30;  // 抖动>30%开始降帧
static constexpr double JITTER_UPPER_RATIO = 1.00;  // 抖动100%降到最低帧率
static constexpr double JITTER_RESTORE_RATIO = 0.20; // 抖动<20%可升帧
```

#### 抖动比例→帧率映射表（60fps示例）

| 抖动比例(R) | 期望间隔 | 实际抖动 | 状态 | 目标帧率 | 服务器帧率 |
|-------------|----------|----------|------|----------|------------|
| ≤20% | 16.7ms | ≤3.3ms | **✅可升帧** | 逐步恢复 | - |
| 20%-30% | 16.7ms | 3.3-5ms | 正常 | 保持 | - |
| **30%** | 16.7ms | **5ms** | **⚠️预警** | **60fps** | 240fps |
| 50% | 16.7ms | 8.3ms | ⚠️波动 | 47fps | 188fps |
| 65% | 16.7ms | 10.8ms | 🚨严重 | 37fps | 148fps |
| 80% | 16.7ms | 13.3ms | 🚨危急 | 28fps | 112fps |
| **100%** | 16.7ms | **16.7ms** | **🚨极端** | **15fps** | 60fps |

#### 降帧公式

```
抖动比例映射：30%→100% = 60fps→15fps
targetFps = 60 - 45 × (R - 0.30) / 0.70
```

#### 降帧触发条件（满足任一即可）

| 条件 | 阈值 | 帧率映射 | 说明 |
|------|------|----------|------|
| **条件1：抖动预警⭐** | **抖动比例 > 30%** | **60fps→15fps** | **预测式！帧间隔不稳定** |
| 条件2：队列不足 | 队列 < 动态阈值×35% | 1:1映射 | 响应式备用 |
| 条件3：延迟过高 | 延迟 > 270ms | 60fps→15fps | 响应式备用 |
| **条件4：到达帧率不足⭐** | **到达率 < 85%** | **60fps→15fps** | **v8.4新增！帧到达数量不足** |

> **v8.4 新增条件4说明**：
> - 抖动检测只能感知**帧间隔不稳定**（忽快忽慢）
> - 但无法感知**稳定地慢**（均匀地低于配置帧率）
> - 条件4直接监测到达帧率是否充足，补充抖动检测的盲区
> - 例如：配置35fps，到达24fps，到达率=68%<85%，触发降帧

#### 升帧触发条件（需同时满足）

| 条件 | 阈值 | 说明 |
|------|------|------|
| **条件1：抖动稳定⭐** | **抖动比例 < 20%** | **网络恢复稳定** |
| **条件2：到达帧率充足⭐** | **到达率 ≥ 85%** | **v8.4新增！帧到达数量充足** |
| 条件3：队列充足 | 队列 ≥ 动态阈值×70% | 队列有足够缓冲 |
| 条件4：延迟正常 | 270-470ms | 延迟在合理范围 |
| 条件5：持续时间 | 1秒 | 防止抖动 |

#### 升帧公式（指数平滑回升）

```
升帧幅度 = 当前推流帧率 × 10%
目标帧率 = min(当前帧率 + 升帧幅度, 到达帧率, 原始帧率)

示例：
┌─────────────┬────────────┬──────────────┐
│ 当前推流fps │ 升帧幅度   │ 目标帧率     │
├─────────────┼────────────┼──────────────┤
│ 15fps       │ +1fps(10%) │ 16fps        │
│ 20fps       │ +2fps(10%) │ 22fps        │
│ 30fps       │ +3fps(10%) │ 33fps        │
│ 45fps       │ +4fps(10%) │ 49fps        │
│ 55fps       │ +5fps(10%) │ 60fps        │
└─────────────┴────────────┴──────────────┘
```

#### 代码实现

```cpp
// ⭐⭐⭐ v8.4核心：帧间隔抖动检测 + 到达帧率检测
// 在帧到达时计算抖动
double expectedInterval = 1000.0 / fps;
double actualInterval = nowMs - lastFrameMs;
double jitter = abs(actualInterval - expectedInterval);
m_jitterEma = 0.3 * jitter + 0.7 * m_jitterEma;
double jitterRatio = m_jitterEma / expectedInterval;

// ⭐v8.4新增：计算到达帧率比例
double arrivalRatio = arrivalFps / fps;

// 降帧条件（满足任一）
bool jitterTrigger = (jitterRatio > 0.30);  // ⭐预测式！帧间隔不稳定
bool queueTrigger = (queueDepth < lowerThreshold);
bool delayTrigger = (appDelayMs > 270);
bool arrivalTrigger = (arrivalRatio < 0.85);  // ⭐v8.4新增：到达帧率<85%

if (jitterTrigger || queueTrigger || delayTrigger || arrivalTrigger) {
    // 抖动映射：30%→100% = 60fps→15fps
    double ratio = (jitterRatio - 0.30) / 0.70;
    int targetFps = 60 - 45 * ratio;
    
    // ⭐v8.4核心：到达帧率映射，目标帧率不能超过实际到达帧率！
    if (arrivalTrigger) {
        double arrivalLowerRatio = (0.85 - arrivalRatio) / 0.35;  // 85%→50%
        int arrivalTargetFps = 60 - 45 * arrivalLowerRatio;
        arrivalTargetFps = min(arrivalTargetFps, (int)arrivalFps);  // 不超过到达帧率
        targetFps = min(targetFps, arrivalTargetFps);
    }
    
    emit requestFpsChange(targetFps);
}

// 升帧条件（需同时满足）
bool canRestore = (jitterRatio < 0.20)      // ⭐抖动稳定
               && (arrivalRatio >= 0.85)    // ⭐v8.4新增：到达帧率充足
               && (queueDepth >= upperThreshold)
               && (appDelayMs >= 270 && appDelayMs <= 470);
if (canRestore) {
    int stepFps = max(1, currentFps * 0.10);  // 10%步进
    int targetFps = currentFps + stepFps;
    targetFps = min(targetFps, arrivalFps);   // 不超过到达帧率
    emit requestFpsChange(targetFps);
}
```

#### 到达帧率映射表

| 配置fps | 到达fps | 到达率 | 触发？ | 目标fps |
|---------|---------|--------|--------|---------|
| 35fps | 35fps | 100% | ❌ 不触发 | 保持 |
| 35fps | 30fps | 85.7% | ❌ 不触发 | 保持 |
| 35fps | 29fps | 82.9% | ✅ 触发 | 降到29fps |
| 35fps | 24fps | 68.6% | ✅ 触发 | 降到24fps |
| 60fps | 45fps | 75% | ✅ 触发 | 降到45fps |
| 60fps | 30fps | 50% | ✅ 触发 | 降到30fps |

#### 日志示例

```
📊 状态[✅正常] | 到达=35fps(100%) 配置=35fps | 队列=8/8帧 水位=100% | 速度=100% | 延迟≈580ms | 抖动=15%
📊 状态[⬆️恢复] | 到达=24fps(68%) 配置=35fps | 队列=4/8帧 水位=50% | 速度=89% | 延迟≈520ms | 抖动=45%
📉 降帧 | 📉到达=24fps(配置35fps,比例68%) | 35fps→24fps (服务器:140→96) | 队列=4/8帧 延迟=167ms 抖动=45%
📉 降帧 | 🔮抖动=70%(>30%) | 47fps→34fps (服务器:188→136) | 队列=8/13帧 延迟=133ms 抖动=70%
📈 升帧 | 抖动=15%(<20%) 队列=10帧≥9帧 | 34fps→37fps(+3,10%) | 到达=50fps
```

#### v8.4诊断日志（新增）

```
🔍 v8.4诊断 | 抖动EMA=13.1ms 比例=45% | 到达=24fps(68%) | 触发=[抖动45%>30% 到达68%<85%] | 计数=1
🔍 v8.4诊断 | 抖动EMA=5.2ms 比例=18% | 到达=35fps(100%) | 触发=[无] | 计数=0
```

诊断日志帮助理解：
- **抖动EMA**：当前帧间隔抖动的平滑值（ms）
- **抖动比例**：抖动/期望间隔（>30%触发降帧）
- **到达率**：到达帧率/配置帧率（<85%触发降帧）
- **触发条件**：当前满足哪些降帧条件
- **计数**：满足条件的持续次数（需达到阈值才真正降帧）

#### v8.4 新增：播放速度平滑（解决播放不平顺问题）

**问题**：队列深度快速波动 → 速度快速变化 → 帧间隔变化 → 播放抖动

**解决方案**：对队列深度做**EMA平滑**后再调整速度

```cpp
// v8.4: 队列深度EMA平滑
static constexpr double QUEUE_EMA_ALPHA = 0.2;  // 平滑系数（更平滑）
m_queueDepthEma = 0.2 * queueDepth + 0.8 * m_queueDepthEma;

// 使用平滑后的队列深度调整速度
if (smoothQueueDepth >= queueOptimal) {
    m_targetRate = 1.0 + ratio * 0.10;  // 平滑加速
} else if (smoothQueueDepth >= queueMin) {
    m_targetRate = 0.95 + ratio * 0.05;  // 平滑减速
}
```

**效果对比**：
```
v8.3（无平滑）：
  队列: 4→8→5→9→4→7→... (快速波动)
  速度: 85%→100%→95%→100%→85%→98%→... (跳动！)

v8.4（EMA平滑）：
  队列: 4→8→5→9→4→7→...
  EMA:  4→4.8→4.8→5.6→5.3→5.6→... (平滑)
  速度: 95%→96%→96%→97%→97%→97%→... (稳定！)
```

#### ⭐⭐⭐ v8.5 修复：渲染间隔只基于配置帧率（彻底解决卡顿）

**v8.4问题**：使用`min(configFps, arrivalFps)`计算间隔，当到达帧率从30fps波动到10fps时，渲染间隔从33ms变成100ms（3倍变化！），即使有15%变化限制，也需要很多帧才能追上，中间造成明显卡顿。

**v8.5修复**：
1. 渲染间隔**只**基于配置帧率，完全不使用到达帧率
2. EMA平滑系数从0.3降到0.05（相当于20帧平滑）
3. 间隔变化限制从15%降到2%
4. 当队列不足时，显示上一帧（不减慢间隔）

```cpp
// v8.5：只用配置帧率，保持渲染间隔稳定
double safeConfigFps = qMax(10.0, m_configFps);
double safePlaybackRate = qBound(0.85, m_playbackRate, 1.10);  // 正常模式
double baseInterval = 1000.0 / safeConfigFps / safePlaybackRate;

// 极度保守的EMA平滑
constexpr double ULTRA_SMOOTH_ALPHA = 0.05;  // 相当于20帧平滑
m_intervalEma = ULTRA_SMOOTH_ALPHA * baseInterval + (1.0 - ULTRA_SMOOTH_ALPHA) * m_intervalEma;

// 间隔变化限制2%
double maxChange = s_lastInterval * 0.02;  // 30fps时每帧最多变化0.66ms
// v8.4问题：降帧到15fps后，配置仍是30fps，消费30fps>到达15fps，队列耗尽！
// v8.4解决：effectiveFps = min(configFps, arrivalFps)
// v8.5问题：v8.4的min()导致间隔波动（33ms→100ms），造成卡顿
// v8.5解决：只用configFps，队列不足时显示上一帧（不减慢间隔）

// 正确方案（v8.4）：取配置帧率和到达帧率中的较小值！
double safeConfigFps = qMax(10.0, m_configFps);
double safeArrivalRate = qMax(10.0, m_arrivalRateEma);  // 已EMA平滑
double effectiveFps = qMin(safeConfigFps, safeArrivalRate);  // ⭐取较小值！
double baseInterval = 1000.0 / effectiveFps / safePlaybackRate;
```

**v8.4原理（已被v8.5替代）**：
```
// v8.4使用min()，但仍然会因为到达帧率波动导致卡顿
场景1：网络抖动（到达波动，配置稳定）
  到达帧率: 28fps→33fps→30fps→35fps (波动，EMA≈30fps)
  配置帧率: 30fps
  effectiveFps = min(30, 30) = 30fps
  渲染间隔: 33ms (理论稳定)
  ⚠️实际问题：EMA有滞后，瞬时波动仍会影响间隔
```

**⭐v8.5原理（当前方案）**：
```
// v8.5只用配置帧率，彻底隔离到达帧率波动
场景1：正常网络抖动
  配置帧率: 30fps
  渲染间隔: 33ms (稳定！完全不受到达帧率影响)
  队列处理: 到达下降 → 队列减少 → 降速(85%) → 间隔39ms → 恢复

场景2：第二道防线降帧（请求iOS降帧，配置还未更新）
  配置帧率: 30fps（等待setConfigFps更新）
  渲染间隔: 33ms (仍然稳定！)
  队列处理: 到达<消耗 → 队列减少 → 显示上一帧 → 等待恢复
  
场景3：配置帧率更新后
  配置帧率: 15fps（setConfigFps已调用）
  渲染间隔: 66ms (自动适应)
  
⭐关键区别：
v8.4: 到达15fps→间隔立即变66ms→用户感觉卡顿
v8.5: 到达15fps→间隔仍33ms→队列不足显示上一帧→平滑过渡
```

#### v8.3 vs v8.2 对比

| 特性 | v8.2 | v8.3 |
|------|------|------|
| **感知方式** | 响应式（队列见底才响应） | **预测式（抖动提前感知）⭐** |
| **提前量** | 0秒 | **0.5-1秒** |
| 降帧触发 | 队列/延迟 | **抖动>30%（主）+ 队列/延迟（备）** |
| 升帧触发 | 队列/延迟 | **抖动<20% + 队列稳定** |
| 升帧算法 | 到达帧率×10% | **当前帧率×10%** |
| 网络波动响应 | 慢（等队列下降） | **快（帧间隔立即反映）** |

---

### 15.16 队列为空紧急应对方案（⭐ 最后兜底）

#### 核心原则

```
┌─────────────────────────────────────────────────────────────┐
│           降帧策略总结                                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  🔮 主要靠：帧间隔抖动预测（提前0.5-1秒感知）               │
│     • 抖动比例 > 30% → 触发降帧                             │
│     • 这是第一优先级，能提前感知网络波动                     │
│                                                             │
│  ⚠️ 队列为空：只是最后兜底（不主动降帧）                    │
│     • 如果接收fps正常 → 只降速度，不降帧                    │
│     • 如果接收fps严重下降 → 才触发紧急降帧                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### 问题分析

即使有抖动预测，极端情况仍可能导致队列见底：

| 场景 | 原因 | 接收fps | 处理方式 |
|------|------|---------|----------|
| **消耗过快** | 播放速度>到达速度 | **正常** | 只降速度 |
| **网络瞬断** | WiFi切换、信号丢失 | **骤降** | 紧急降帧 |
| **iOS中断** | App后台、设备过热 | **骤降** | 紧急降帧 |

#### 关键判断：接收fps比例

```cpp
double arrivalRatio = m_arrivalRateEma / m_configFps;

if (arrivalRatio >= 50%) {
    // 网络正常，只是消耗过快
    → 只降速度，不降帧
} else {
    // 网络严重恶化
    → 触发紧急降帧
}
```

#### 两种处理模式

```
┌─────────────────────────────────────────────────────────────┐
│  模式1：消耗过快（接收fps ≥ 50%配置）                       │
├─────────────────────────────────────────────────────────────┤
│  日志：⚠️ 队列见底[消耗过快] | 到达=20fps(正常) | 只降速度  │
│                                                             │
│  操作：                                                     │
│    🐢 播放速度 → 50%（减少消耗，等队列恢复）               │
│    📺 显示最后帧（防黑屏）                                  │
│                                                             │
│  不做：                                                     │
│    ❌ 不降帧（网络正常，没必要）                            │
│    ❌ 不请求PLI（没有网络问题）                             │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  模式2：网络严重恶化（接收fps < 50%配置）                   │
├─────────────────────────────────────────────────────────────┤
│  日志：🚨 队列见底[网络严重] | 到达=5fps(<25fps×50%) | 自救 │
│                                                             │
│  操作：                                                     │
│    📉 紧急降帧 → 15fps（减轻网络压力）                     │
│    🐢 播放速度 → 50%                                       │
│    📨 请求关键帧PLI（恢复时减少花屏）                       │
│    📺 显示最后帧                                            │
└─────────────────────────────────────────────────────────────┘
```

#### 代码实现

```cpp
} else if (queueDepth == 0) {
    if (!m_emergencyHold) {
        m_emergencyHold = true;
        m_emptyQueueCount++;
        
        // ⭐⭐⭐ 关键判断：接收fps是否正常
        double arrivalRatio = m_arrivalRateEma / m_configFps;
        bool networkSevereDrop = (arrivalRatio < 0.5);  // <50%才算严重
        
        if (networkSevereDrop) {
            // 网络严重恶化：完整自救
            qDebug() << "🚨 队列见底[网络严重] | 启动紧急自救";
            if (!m_emergencyFpsLowered) {
                m_emergencyFpsLowered = true;
                emit requestFpsChange(15);  // 降帧
            }
            QMetaObject::invokeMethod(this, "requestKeyFrame", Qt::QueuedConnection);
        } else {
            // 网络正常但消耗过快：只降速度
            qDebug() << "⚠️ 队列见底[消耗过快] | 只降速度不降帧";
        }
        
        // 共同：降速度
        m_playbackRate = R_EMERGENCY_MIN;  // 50%
    }
    lowWaterHold = true;  // 使用最后有效帧
}
```

#### 日志示例

```
# 场景1：网络正常，消耗过快（不降帧）
⚠️ 队列见底[消耗过快] | 次数=1 | 到达=20fps(正常) | 只降速度不降帧
   🐢 速度降低: 100%→50%
✅ 紧急保护解除 | 队列=3帧 水位=50% | 恢复耗时=200ms

# 场景2：网络严重恶化（触发紧急降帧）
🚨 队列见底[网络严重] | 次数=1 | 到达=5fps(<25fps×50%) | 启动紧急自救
   📉 [自救] 紧急降帧: 60fps→15fps
   🐢 速度降低: 100%→50%
✅ 紧急保护解除 | 队列=5帧 水位=55% | 恢复耗时=1200ms
```

#### 与抖动预测的关系

| 机制 | 触发时机 | 降帧方式 | 优先级 |
|------|----------|----------|--------|
| **🔮 帧间隔抖动** | 抖动>30%（提前感知） | 平滑降帧 | **主要⭐** |
| 队列不足 | 队列<阈值 | 1:1映射 | 备用 |
| 延迟过高 | 延迟>270ms | 平滑降帧 | 备用 |
| **⚠️ 队列为空** | queueDepth==0 | 只降速度/紧急降帧 | **兜底** |

#### 设计原则

| 原则 | 说明 |
|------|------|
| **抖动预测为主** | 降帧主要靠帧间隔抖动，能提前感知 |
| **队列为空是兜底** | 只在极端情况才触发，且要看接收fps |
| **避免误触发** | 接收fps正常时不降帧，只降速度 |
| **避免黑屏** | 任何情况都显示最后帧 |

---

## 16. 截图帧索引分析（缓冲队列影响）

### 16.1 问题描述

| 版本 | 队列 | 截图逻辑 | 准确性 |
|------|------|----------|--------|
| **V1（无队列）** | 无 | `newestFrame()` = 正在渲染的帧 | ✅ 精确 |
| **V2（有队列）** | 150-400ms缓冲 | `newestFrame()` = 最新到达的帧 | ❌ 超前用户画面 |

### 16.2 数据流分析

```
                    ┌─────────────────────────────────────────┐
                    │            GStreamer Pipeline           │
                    │         (H264解码 → BGRA转换)           │
                    └─────────────────────────────────────────┘
                                       │
                                       ▼
                              ┌─────────────┐
                              │     tee     │  (分流器)
                              └─────────────┘
                               │           │
       ┌───────────────────────┘           └───────────────────────┐
       ▼                                                           ▼
┌──────────────────┐                                   ┌──────────────────┐
│   JPEG 编码分支   │ (立即编码保存)                    │    显示分支       │
│    jpegQueue     │                                   │   displayQueue   │
│    jpegEnc       │                                   │    appsink       │
│  multifilesink   │                                   └──────────────────┘
└──────────────────┘                                             │
       │                                                          ▼
       ▼                                               ┌──────────────────┐
┌──────────────────┐                                   │  m_frameQueue    │ ← 应用层缓冲
│ m_jpegFrameIndex │ = 12                              │  [帧5,6,7,8,9]   │   (9帧=150ms)
│  newestFrame()   │                                   └──────────────────┘
└──────────────────┘                                             │
                                                                 ▼
                                                       ┌──────────────────┐
                                                       │  用户看到: 帧5    │ ← 正在渲染
                                                       └──────────────────┘
```

### 16.3 问题量化

```
假设 @60fps，队列深度 9帧：

JPEG保存进度:    1  2  3  4  5  6  7  8  9  10 11 12
                                              ↑
                                      jpegFrameIndex = 12

应用层队列:                       [5  6  7  8  9]
                                  ↑
                            正在渲染帧5

用户点击截图时:
  - newestFrame() = 12（最新保存的帧）
  - 用户实际看到 = 帧5
  - 差距 = 12 - 5 = 7帧 ≈ 117ms

结果：截图比用户看到的画面"超前"约117ms！
```

### 16.4 影响分析

| 场景 | 影响 | 严重程度 |
|------|------|----------|
| **实时流截图** | 截图比用户看到的画面超前100-200ms | ⚠️ 中等 |
| **慢放截图** | ✅ 不受影响（使用 `currentGlobalFrameIndex`） | ✅ 无 |
| **前抓拍/后抓拍** | 帧偏移计算需要修正 | ⚠️ 需处理 |

### 16.5 解决方案

#### 方案：队列深度偏移修正

在截图时，用队列深度修正帧索引：

```cpp
// capturemanager.cpp - capture() 函数
if (m_gstPlayer) {
    qint64 newestIdx = m_gstPlayer->newestFrame();
    int queueDepth = m_gstPlayer->bufferSize();  // 当前队列深度
    
    // ⭐ 修正：用户看到的是"最新帧 - 队列深度"
    eventIndex = newestIdx - queueDepth;
    eventIndex = qMax(m_gstPlayer->oldestFrame(), eventIndex);  // 确保不小于oldest
    
    qDebug() << "📷 截图修正 | newest=" << newestIdx 
             << "队列=" << queueDepth 
             << "修正后=" << eventIndex;
}
```

### 16.6 修正效果

```
修正前：
  newestFrame() = 12
  用户看到 = 帧5
  截图帧 = 12 ❌

修正后：
  newestFrame() = 12
  queueDepth = 7
  eventIndex = 12 - 7 = 5 ✅
  
  截图帧 = 5（与用户看到的一致）
```

### 16.7 注意事项

| 注意 | 说明 |
|------|------|
| **队列深度瞬时变化** | 使用截图瞬间的 `bufferSize()` |
| **空队列保护** | 当 queueDepth=0 时不修正 |
| **慢放不受影响** | 慢放使用独立的 `currentGlobalFrameIndex()` |
| **前后抓拍范围** | 前抓拍-后抓拍的帧范围计算需要基于修正后的 eventIndex |

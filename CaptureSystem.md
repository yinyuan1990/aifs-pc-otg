# 📷 截图系统技术文档

## 1. 系统概述

麒麟客户端的截图系统支持两种模式：
- **实时流截图**：捕获当前正在观看的画面
- **慢放截图**：捕获慢放回放中的画面

每次截图会保存：
- **事件帧**：用户点击截图时看到的那一帧
- **前抓拍帧**：事件帧之前的若干帧
- **后抓拍帧**：事件帧之后的若干帧

---

## 2. 数据流架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         iOS 推流 (WebRTC)                           │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      GStreamer Pipeline                             │
│  webrtcbin → rtph264depay → h264parse → decoder → videoconvert      │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                           ┌───────────────┐
                           │      tee      │  (分流器)
                           └───────────────┘
                             │           │
            ┌────────────────┘           └────────────────┐
            ▼                                             ▼
   ┌─────────────────┐                         ┌─────────────────┐
   │   JPEG 编码分支  │                         │    显示分支     │
   │  (multifilesink)│                         │   (appsink)    │
   └─────────────────┘                         └─────────────────┘
            │                                             │
            ▼                                             ▼
   ┌─────────────────┐                         ┌─────────────────┐
   │  JPEG 文件存储   │                         │  m_frameQueue   │
   │ s_xxx_000001.jpg│                         │  (缓冲队列)     │
   │ s_xxx_000002.jpg│                         └─────────────────┘
   │      ...        │                                    │
   └─────────────────┘                                    ▼
                                               ┌─────────────────┐
                                               │  QVideoSink     │
                                               │  (用户看到)     │
                                               └─────────────────┘
```

---

## 3. 帧索引系统

### 3.1 核心变量

| 变量 | 类型 | 说明 |
|------|------|------|
| `m_jpegFrameIndex` | `qint64` | JPEG保存的最新帧索引 |
| `m_oldestFrame` | `qint64` | 最老的可用帧索引 |
| `m_frameQueue` | `QList<GstSample*>` | 应用层缓冲队列 |
| `m_bufferSize` | `int` | 当前队列深度 |

### 3.2 帧文件命名

```
格式: {sessionPrefix}_{frameIndex:9位}.jpeg
示例: s_1737012345_000000123.jpeg
       ↑              ↑
   会话前缀      9位帧索引(前补0)
```

### 3.3 会话前缀

每次 WebRTC 连接成功时生成新的会话前缀，格式为 `s_时间戳`。

**作用**：断线重连后，旧抓拍的帧文件仍能找到（使用保存的会话前缀）。

---

## 4. 缓冲队列与截图修正（⭐重要）

### 4.1 问题背景

引入缓冲队列后，用户看到的画面与最新到达的帧之间有延迟：

```
                时间线 →
帧到达:     1  2  3  4  5  6  7  8  9  10 11 12
                                          ↑
                                  jpegFrameIndex = 12 (最新保存)

缓冲队列:                   [5  6  7  8  9] (9帧缓冲)
                             ↑
                        正在渲染帧5 (用户看到)

差距: 12 - 5 = 7帧 ≈ 117ms (@60fps)
```

### 4.2 修正算法

```cpp
// capturemanager.cpp - capture() 函数

if (m_gstPlayer) {
    qint64 newestIdx = m_gstPlayer->newestFrame();   // 最新保存的帧
    int queueDepth = m_gstPlayer->bufferSize();      // 队列深度
    qint64 oldestIdx = m_gstPlayer->oldestFrame();   // 最老可用帧
    
    // ⭐ 修正：用户实际看到的帧 = 最新帧 - 队列深度
    eventIndex = newestIdx - queueDepth;
    eventIndex = qMax(oldestIdx, eventIndex);        // 边界保护
}
```

### 4.3 修正效果

| 场景 | 修正前 | 修正后 |
|------|--------|--------|
| newest=12, queue=7 | eventIndex=12 ❌ | eventIndex=5 ✅ |
| newest=100, queue=9 | eventIndex=100 ❌ | eventIndex=91 ✅ |
| newest=5, queue=10 | eventIndex=5 ❌ | eventIndex=oldest ✅ |

---

## 5. 截图模式

### 5.1 实时流截图

```cpp
void CaptureManager::capture()
{
    qint64 eventIndex = -1;
    
    if (m_slowMotionActive && m_slowMotionPlayer) {
        // 慢放模式（见5.2）
    } else {
        // ⭐ 实时流模式：使用修正后的帧索引
        if (m_gstPlayer) {
            qint64 newestIdx = m_gstPlayer->newestFrame();
            int queueDepth = m_gstPlayer->bufferSize();
            eventIndex = newestIdx - queueDepth;
            eventIndex = qMax(m_gstPlayer->oldestFrame(), eventIndex);
        }
    }
    
    // 计算前后抓拍范围
    qint64 startIndex = eventIndex - m_preFrameCount;
    qint64 endIndex = eventIndex + m_postFrameCount;
    
    // 创建 CaptureItem...
}
```

### 5.2 慢放截图

慢放模式不需要队列修正，因为使用的是 `SlowMotionPlayer` 的当前帧索引：

```cpp
if (m_slowMotionActive && m_slowMotionPlayer) {
    // 慢放模式：直接使用慢放当前帧的全局索引
    eventIndex = m_slowMotionPlayer->currentGlobalFrameIndex();
}
```

---

## 6. 前抓拍/后抓拍

### 6.1 参数配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `m_preFrameCount` | 300 | 前抓拍帧数 |
| `m_postFrameCount` | 300 | 后抓拍帧数 |

### 6.2 帧范围计算

```cpp
qint64 startIndex = eventIndex - m_preFrameCount;  // 前抓拍起点
qint64 endIndex = eventIndex + m_postFrameCount;   // 后抓拍终点

// 边界保护
startIndex = qMax(oldestFrame, startIndex);

// 慢放模式限制在录制范围内
if (m_slowMotionActive) {
    endIndex = qMin(endIndex, m_slowMotionPlayer->endIndex());
}
```

### 6.3 事件帧偏移

```cpp
// 事件帧在抓拍范围内的偏移
int eventOffset = eventIndex - startIndex;

// 示例: eventIndex=500, startIndex=200
// eventOffset = 500 - 200 = 300
// 即：第300帧是事件帧
```

---

## 7. CaptureItem 数据结构

```cpp
struct CaptureItem {
    qint64 startIndex;      // 抓拍起始帧索引
    qint64 endIndex;        // 抓拍结束帧索引
    int currentOffset;      // 当前查看的帧偏移
    QString sessionPrefix;  // 会话前缀（用于读取JPEG文件）
    
    // 计算属性
    int eventOffset() const { return eventIndex - startIndex; }
    int frameCount() const { return endIndex - startIndex + 1; }
};
```

---

## 8. 帧图像读取

### 8.1 读取流程

```cpp
QImage CaptureManager::getFrameImage(int itemIndex, int frameOffset)
{
    const CaptureItem &item = m_items[itemIndex];
    qint64 globalIndex = item.startIndex + frameOffset;
    
    // 1. 尝试从内存（GpuPipeline）读取
    if (m_gpuPipeline) {
        QImage img = m_gpuPipeline->decodeFrameToImage(globalIndex);
        if (!img.isNull()) return img;
    }
    
    // 2. 从磁盘读取 JPEG
    QImage img = loadFrameFromDisk(globalIndex, item.sessionPrefix);
    
    return img;
}
```

### 8.2 磁盘读取

```cpp
QImage CaptureManager::loadFrameFromDisk(qint64 globalIndex, const QString &sessionPrefix)
{
    if (m_gstPlayer) {
        QByteArray jpegData = m_gstPlayer->getJpegWithPrefix(globalIndex, sessionPrefix);
        if (!jpegData.isEmpty()) {
            QImage img;
            img.loadFromData(jpegData, "JPEG");
            return img;
        }
    }
    return QImage();
}
```

---

## 9. 日志示例

### 9.1 实时流截图（有修正）

```
📷 Capture: gpuPipeline=有效, slowMotionActive=false
📷 Capture (Realtime): newest=12345 队列=9 修正后eventIndex=12336
📷 Capture: eventIndex=12336 preCount=300 postCount=300
📷 Capture: 最终范围 12036-12636 总帧数=601
```

### 9.2 慢放截图

```
📷 Capture: gpuPipeline=有效, slowMotionActive=true
📷 Capture (SlowMotion): eventIndex=11500 currentFrame=150 
                         startIndex=11000 endIndex=12000 recordedFrames=1000
📷 Capture: 最终范围 11200-11800 总帧数=601
```

### 9.3 帧索引同步诊断日志（⭐排查用）

当打开截图item放大查看时，会输出以下诊断日志：

```
# ImageProvider 请求日志
🔍 ImageProvider | item=0 | QML请求frameOffset=150 | Manager.currentOffset=150 | 是否同步=✅

# CaptureManager 响应日志
🖼️ getFrameImage | item=0 | 请求frameOffset=150 | item.currentOffset=150 | item.eventOffset=300 | 是否同步=✅
```

**不同步示例（需要排查）**：

```
🔍 ImageProvider | item=0 | QML请求frameOffset=200 | Manager.currentOffset=150 | 是否同步=❌不同步!
🖼️ getFrameImage | item=0 | 请求frameOffset=200 | item.currentOffset=150 | item.eventOffset=300 | 是否同步=❌
```

**排查方向**：
- QML请求的frameOffset与Manager记录的currentOffset不一致
- 可能是QML层面的状态没有同步更新
- 或者gotoFrame()没有被正确调用

---

## 10. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | - | 无缓冲队列，直接使用 newestFrame() |
| v2.0 | 2026-01 | 引入缓冲队列，添加队列深度修正 |

---

## 11. 注意事项

1. **队列深度瞬时变化**：修正使用截图瞬间的 `bufferSize()`，可能有微小误差
2. **会话前缀重要性**：断线重连后会生成新前缀，旧抓拍需要使用保存的旧前缀
3. **慢放不受影响**：慢放使用独立的帧索引系统
4. **后抓拍帧延迟**：后抓拍的帧需要等待到达，滚轮查看时文件可能还未保存

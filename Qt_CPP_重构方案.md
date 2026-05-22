# Qt C++ 重构方案文档

## 核心功能架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        应用核心功能链                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌─────────────┐      ┌─────────────┐      ┌─────────────┐            │
│   │   实时流     │ ───→ │  色度调节   │ ───→ │   显示      │            │
│   │  WebRTC     │      │ 亮度/对比度  │      │  GPU渲染    │            │
│   │  H.264     │      │ 饱和度/伽马  │      │             │            │
│   └──────┬──────┘      └──────┬──────┘      └─────────────┘            │
│          │                    │                                         │
│          │                    │ (色度已应用)                             │
│          │                    ▼                                         │
│          │             ┌─────────────┐      ┌─────────────┐            │
│          │             │   抓拍      │ ───→ │  保存       │            │
│          │             │  前后60帧   │      │  MP4/JPEG   │            │
│          │             └──────┬──────┘      └─────────────┘            │
│          │                    │                                         │
│          │                    │ (带色度的数据)                           │
│          │                    ▼                                         │
│          │             ┌─────────────┐      ┌─────────────┐            │
│          │             │   慢放      │ ───→ │   显示      │            │
│          │             │ 0.1x~2.0x  │      │  帧步进     │            │
│          │             └──────┬──────┘      └─────────────┘            │
│          │                    │                                         │
│          │                    ▼                                         │
│          │             ┌─────────────┐      ┌─────────────┐            │
│          │             │ 慢放中抓拍  │ ───→ │  保存       │            │
│          │             │  当前帧     │      │   JPEG      │            │
│          │             └─────────────┘      └─────────────┘            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

⭐ 关键点：色度调节在实时流阶段应用，后续所有数据都带有色度效果
```

### 四大核心功能

| 功能 | 输入 | 处理 | 输出 |
|------|------|------|------|
| **① 实时流** | WebRTC H.264 | 解码 → 色度调节 → 渲染 | 屏幕显示 |
| **② 抓拍** | 色度后的帧 | 前后60帧 → 编码 | MP4/JPEG |
| **③ 慢放** | 抓拍的 MP4 | 变速解码 → 渲染 | 帧步进显示 |
| **④ 慢放抓拍** | 慢放当前帧 | 编码 JPEG | 单帧图片 |

### ⭐ 抓拍功能约束

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         抓拍系统设计约束                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   最大抓拍数量：100 个 item                                              │
│   每个抓拍帧数：最多 120 帧（前 60 + 后 60）                             │
│   总帧数上限：  100 × 120 = 12,000 帧                                   │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                      抓拍 Item 网格                              │  │
│   │   ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐                      │  │
│   │   │ #1  │ │ #2  │ │ #3  │ │ ... │ │#100 │  ← 最多 100 个       │  │
│   │   │120帧│ │120帧│ │120帧│ │     │ │120帧│                      │  │
│   │   └─────┘ └─────┘ └─────┘ └─────┘ └─────┘                      │  │
│   │                                                                 │  │
│   │   满载后：FIFO 覆盖最旧的 item                                   │  │
│   └─────────────────────────────────────────────────────────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 抓拍存储方案对比

| 方案 | 单 Item | 100 Item 满载 | 内存占用 | 可行性 |
|------|---------|---------------|----------|--------|
| **BGRA 原始帧** | 120 × 10MB = 1.2GB | **120 GB** | 全内存 | ❌ 不可行 |
| **JPEG 序列** | 120 × 300KB = 36MB | **3.6 GB** | 全内存 | ❌ 太大 |
| **JPEG 磁盘** | 120 × 300KB = 36MB | 3.6 GB 磁盘 | ~200MB | ⚠️ I/O 慢 |
| **MP4 片段** ⭐ | 2秒 × 4MB/s = 8MB | **800 MB 磁盘** | ~200MB | ✅ 推荐 |

### ⭐ 推荐方案：MP4 片段 + 按需解码

```
存储设计：

  内存（运行时）：
  ├── 压缩帧环形缓冲（实时抓拍用）     15-30 MB
  ├── 100 个 CaptureItem 元数据        ~1 MB
  │   ├── item_id, timestamp
  │   ├── mp4_path: "captures/cap_001.mp4"
  │   ├── total_frames: 120
  │   ├── event_frame: 60 (触发帧位置)
  │   └── thumbnail: 缩略图指针
  ├── 缩略图缓存（每 item 1 张）       100 × 50KB = 5 MB
  └── 当前查看的解码帧（5帧窗口）      5 × 10MB = 50 MB
  ────────────────────────────────────────────────────
  总内存：约 100 MB（抓拍模块）

  磁盘（持久化）：
  └── captures/
      ├── cap_001.mp4    (8 MB)
      ├── cap_002.mp4    (8 MB)
      ├── ...
      └── cap_100.mp4    (8 MB)
      ────────────────────
      总磁盘：约 800 MB（满载）
```

### 抓拍 Item 数据结构

```cpp
// 抓拍 Item 元数据（内存中）
struct CaptureItem {
    int id;                          // 唯一 ID
    QString mp4Path;                 // MP4 文件路径
    int totalFrames;                 // 总帧数 (≤120)
    int eventFrame;                  // 触发帧位置 (通常 60)
    int preFrames;                   // 前帧数 (≤60)
    int postFrames;                  // 后帧数 (≤60)
    qint64 timestamp;                // 抓拍时间戳
    QImage thumbnail;                // 缩略图（事件帧）
    
    // 色度参数快照（抓拍时的设置）
    double brightness;
    double contrast;
    double saturation;
    double gamma;
};

// 抓拍管理器
class CaptureManager : public QObject {
    Q_OBJECT
    Q_PROPERTY(int count READ count NOTIFY countChanged)
    Q_PROPERTY(int maxCount READ maxCount CONSTANT)  // = 100
    
public:
    static constexpr int MAX_ITEMS = 100;
    static constexpr int MAX_FRAMES_PER_ITEM = 120;
    static constexpr int DEFAULT_PRE_FRAMES = 60;
    static constexpr int DEFAULT_POST_FRAMES = 60;
    
    // 触发抓拍
    void capture() {
        if (m_items.size() >= MAX_ITEMS) {
            // FIFO：删除最旧的
            removeOldest();
        }
        
        CaptureItem item;
        item.id = m_nextId++;
        item.mp4Path = QString("captures/cap_%1.mp4").arg(item.id, 5, 10, QChar('0'));
        item.preFrames = qMin(DEFAULT_PRE_FRAMES, m_ringBuffer.preFrameCount());
        item.postFrames = DEFAULT_POST_FRAMES;
        item.timestamp = QDateTime::currentMSecsSinceEpoch();
        
        // 从环形缓冲提取前帧 + 等待后帧 + 封装 MP4
        extractAndSaveMp4(item);
        
        m_items.append(item);
        emit countChanged();
        emit itemAdded(item);
    }
    
    // 获取 Item 用于显示
    Q_INVOKABLE CaptureItem* getItem(int index) {
        return &m_items[index];
    }
    
private:
    QVector<CaptureItem> m_items;    // 最多 100 个
    CompressedFrameRing m_ringBuffer; // 压缩帧环形缓冲
    int m_nextId = 1;
};
```

### 色度调节影响范围

```
色度调节参数：亮度 / 对比度 / 饱和度 / 伽马

应用位置：实时流解码后、显示前
         ↓
影响范围：
  ✅ 实时流显示 - 直接看到效果
  ✅ 抓拍数据   - 录制的就是调节后的画面
  ✅ 慢放回放   - 回放的也是调节后的画面
  ✅ 慢放抓拍   - 截取的也是调节后的帧
```

---

## 〇、同类产品分析

### 产品 A：混沌之眼 (HunDun)

```
技术栈：Electron + FFmpeg
├── Electron (Chromium 内核)     # 跨平台 UI，700MB 左右内存
├── ffmpeg.dll                   # FFmpeg 视频处理
├── libEGL.dll / libGLESv2.dll   # OpenGL ES 渲染
└── app.asar                     # JavaScript 业务逻辑
```

### 产品 B：麒麟 (Kirin) ⭐ 重要参考

```
技术栈：C# WPF + CefSharp
├── Kirin.exe                    # C# WPF 主程序
├── CefSharp.*.dll               # Chromium Embedded Framework
├── libcef.dll                   # Chromium 核心 (~200MB)
├── Prism.*.dll                  # WPF MVVM 框架
├── MaterialDesign*.dll          # Material Design UI
├── lua5.1.dll                   # Lua 脚本支持
└── receive.html                 # WebRTC 播放页面
```

**麒麟的核心实现（从 receive.html 分析）：**

```javascript
// 1. WebRTC 连接 - 浏览器原生 API
peerConnection = new RTCPeerConnection(config);
peerConnection.addEventListener('track', gotRemoteMediaStream);

// 2. 视频显示
document.querySelector('#remoteVideo').srcObject = event.streams[0];

// 3. 抓拍实现 - Canvas 截图 + 传给 C#
function drawFrame() {
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    var imageData = canvas.toDataURL("image/jpeg");  // Base64 编码
    window.cefSharpExample.receiveImage(imageData);  // 传给 C#
    requestAnimationFrame(drawFrame);  // 每帧循环
}
```

**麒麟配置 (app.ini)：**
```json
{
  "SelectRow": 4,              // 4 行网格
  "SelectColumn": 3,           // 3 列网格
  "SlowSpeed": 10,             // 慢放速度
  "SelectPreviousNumber": 10,  // 前 10 帧
  "SelectNextNumber": 10       // 后 10 帧
}
```

### 三款产品对比

| 特性 | 混沌之眼 (Electron) | 麒麟 (WPF+CefSharp) | 我们计划 (Qt C++) |
|------|---------------------|---------------------|-------------------|
| **UI 框架** | HTML/JS | WPF + HTML | Qt Quick |
| **WebRTC** | Chromium 原生 | Chromium 原生 | FFmpeg WHIP |
| **视频处理** | FFmpeg | Canvas 截图 | FFmpeg |
| **抓拍方式** | 未知 | Canvas.toDataURL | AVFrame 直接 |
| **抓拍精度** | 未知 | ~16ms (requestAnimationFrame) | **<1ms** |
| **内存占用** | ~700 MB | ~500-700 MB | **400-500 MB** |
| **C#/C++ 通信** | N/A | CefSharp Bridge | 无需（原生） |

### 麒麟方案的问题

```
❌ 每帧都通过 Canvas.toDataURL 转 Base64（CPU 密集）
❌ 每帧都通过 CefSharp Bridge 传给 C#（开销大）
❌ 抓拍精度受限于 requestAnimationFrame（~60fps = 16ms）
❌ 依赖 Chromium（libcef.dll ~200MB）
```

### 我们的优势

```
✅ FFmpeg 直接解码，无 Canvas 转换
✅ AVFrame 精确帧号，<1ms 精度
✅ 原生 C++ 处理，无跨语言开销
✅ 无 Chromium 依赖，体积小
```

这就是为什么他们不需要 GStreamer 的 webrtcbin。

---

## 一、当前 JavaFX 版本痛点分析

### 1.1 抓拍精度问题
| 问题 | 原因 | 影响 |
|------|------|------|
| GPU 与 JPEG 帧差 400-500 帧 | JPEG 编码 + 磁盘 I/O 在独立线程，延迟累积 | 抓拍时刻与显示不同步 |
| 需要手动偏移补偿 | Java 层无法精确获取 GStreamer 内部时间戳 | 用户体验差，需要猜测偏移值 |
| 内存环方案失败 | Java NV12→RGB 转换性能差，CPU 爆到 25% | 无法实现真正实时抓拍 |

### 1.2 稳定性问题
| 问题 | 原因 |
|------|------|
| 马赛克/花屏 | 解码器丢帧、网络抖动、缓冲区不足 |
| 内存泄漏风险 | Java GC 与 GStreamer C 层交互复杂 |
| 偶发崩溃 | JNI 边界错误、GStreamer 回调线程问题 |

### 1.3 性能问题
| 问题 | 原因 |
|------|------|
| 色彩调节卡顿 | Java 层处理图像效率低 |
| 多路流 CPU 占用高 | JavaFX 渲染管线开销 |
| 启动慢 | JVM 预热、类加载 |

---

## 二、技术选型对比

### 2.1 视频框架选型

| 特性 | GStreamer | WebRTC Native SDK | FFmpeg + 自研 | Electron + FFmpeg |
|------|-----------|-------------------|---------------|-------------------|
| **延迟** | 中等 (50-150ms) | 极低 (<50ms) | 可控 | 中等 |
| **抓拍精度** | ★★★★ Pad Probe | ★★★★★ 原生帧控制 | ★★★★ 需自己实现 | ★★★ JS 层有延迟 |
| **多路流** | ★★★★ 成熟 | ★★★☆ 多 PeerConnection | ★★★★ 灵活 | ★★★★ 多 Video 元素 |
| **硬件加速** | ★★★★★ D3D11/NVDEC | ★★★★ 支持但复杂 | ★★★★ 需手动集成 | ★★★★ Chromium 内置 |
| **色彩调节** | ★★★★★ videobalance | ★★☆ 需后处理 | ★★★ 需自己实现 | ★★★ CSS/Canvas |
| **WebRTC 集成** | ★★★☆ webrtcbin 复杂 | ★★★★★ 原生 | ★★☆ 需自己实现 | ★★★★★ 开箱即用 |
| **内存占用** | ★★★★★ 可控 | ★★★★ 可控 | ★★★★★ 可控 | ★★★ ~700MB |
| **学习曲线** | 中等 | 陡峭 | 陡峭 | 低（Web 开发） |

### 2.2 WebRTC 直连 SDK 选项

由于 GStreamer webrtcbin 在 Java 中出现黑屏问题，考虑以下替代方案：

| SDK | 语言 | WebRTC 支持 | 优缺点 |
|-----|------|-------------|--------|
| **libwebrtc** (Google) | C++ | ★★★★★ 完整 | 官方实现，但编译复杂（10GB+） |
| **libdatachannel** | C++ | ★★★★ 轻量 | 仅 DataChannel，无音视频 |
| **AiortcDC** | C++ | ★★★ 基础 | 国产，文档少 |
| **Pion** | Go | ★★★★ 完整 | Go 语言，需 CGO 绑定 |
| **FFmpeg + WHIP** | C | ★★★ 新标准 | FFmpeg 7.0 支持 WHIP/WHEP |

**推荐方案：FFmpeg 7.0 WHIP/WHEP**

```bash
# FFmpeg 7.0+ 支持 WebRTC 直接推拉流（WHIP/WHEP 协议）
# 无需复杂的 libwebrtc 编译

# 拉取 WebRTC 流
ffmpeg -protocol_whitelist "file,http,https,rtp,udp" \
       -i "whep://server/stream" \
       -c copy output.mp4

# 或者使用 FFmpeg 库 API 直接获取帧
```

### 2.3 推荐方案对比

| 方案 | WebRTC 处理 | 视频处理 | 复杂度 |
|------|-------------|----------|--------|
| **A: GStreamer 全套** | webrtcbin | GStreamer | ★★★★ 高 |
| **B: FFmpeg WHIP + GStreamer** | FFmpeg WHIP | GStreamer | ★★★ 中 |
| **C: FFmpeg 全套** | FFmpeg WHIP | FFmpeg | ★★ 低 |
| **D: Electron 参考** | Chromium 内置 | FFmpeg | ★★ 低 |

**推荐方案 B 或 C**：
- FFmpeg 7.0 WHIP 处理 WebRTC 信令和媒体
- GStreamer 或 FFmpeg 处理色彩调节、抓拍

### 2.4 FFmpeg 能力详解

**Q: FFmpeg 在延迟、抓拍精度、色彩调节方面能胜任吗？**

**A: 完全可以，而且更简单：**

| 能力 | GStreamer | FFmpeg | 对比 |
|------|-----------|--------|------|
| **延迟** | 50-150ms | 30-100ms | FFmpeg 更可控 |
| **抓拍精度** | Pad Probe 获取帧 | AVFrame pts 精确 | 两者相当 |
| **色彩调节** | videobalance/gamma | eq/colorbalance/gamma | 两者相当 |
| **GPU 加速** | d3d11/nvdec | d3d11va/cuda/nvdec | 两者相当 |
| **API 复杂度** | 复杂（管线/元素/Pad） | 简单（解码→滤镜→编码） | FFmpeg 更简单 |

#### 2.4.1 FFmpeg 色彩调节

```cpp
// FFmpeg libavfilter 色彩调节（GPU 加速可选）
class FFmpegColorAdjuster {
public:
    void init() {
        // 创建滤镜图：eq（亮度/对比度/饱和度）+ gamma
        const char* filterDesc = 
            "eq=brightness=0:contrast=1:saturation=1:gamma=1";
        
        avfilter_graph_parse2(m_filterGraph, filterDesc, &m_inputs, &m_outputs);
    }
    
    // 实时调节（无需重建滤镜图）
    void setBrightness(double value) {  // -1.0 ~ 1.0
        char cmd[64];
        snprintf(cmd, sizeof(cmd), "brightness=%f", value);
        avfilter_graph_send_command(m_filterGraph, "eq", "brightness", 
                                    std::to_string(value).c_str(), nullptr, 0, 0);
    }
    
    void setContrast(double value) {    // 0.0 ~ 2.0
        avfilter_graph_send_command(m_filterGraph, "eq", "contrast",
                                    std::to_string(value).c_str(), nullptr, 0, 0);
    }
    
    void setSaturation(double value) {  // 0.0 ~ 3.0
        avfilter_graph_send_command(m_filterGraph, "eq", "saturation",
                                    std::to_string(value).c_str(), nullptr, 0, 0);
    }
    
    void setGamma(double value) {       // 0.1 ~ 10.0
        avfilter_graph_send_command(m_filterGraph, "eq", "gamma",
                                    std::to_string(value).c_str(), nullptr, 0, 0);
    }
    
private:
    AVFilterGraph* m_filterGraph;
};
```

#### 2.4.2 FFmpeg 精确帧抓拍

```cpp
// FFmpeg 精确帧捕获（比 GStreamer 更直接）
class FFmpegCaptureManager {
public:
    void processFrame(AVFrame* frame) {
        // ⭐ 精确帧号 - 直接从 AVFrame 获取
        int64_t frameNumber = frame->pts / m_frameDuration;
        
        // ⭐ 判断是否在抓拍窗口内
        if (m_capturePending) {
            int64_t delta = frameNumber - m_captureFrameId;
            if (delta >= -m_preFrames && delta <= m_postFrames) {
                // 直接存储压缩帧（无需重新编码）
                saveCompressedFrame(frame, frameNumber);
            }
        }
        
        m_currentFrameId = frameNumber;
    }
    
    void triggerCapture() {
        m_captureFrameId = m_currentFrameId;
        m_capturePending = true;
    }
    
private:
    std::atomic<int64_t> m_currentFrameId{0};
    std::atomic<int64_t> m_captureFrameId{0};
    std::atomic<bool> m_capturePending{false};
};
```

#### 2.4.3 FFmpeg 低延迟配置

```cpp
// FFmpeg 低延迟解码配置
void setupLowLatencyDecoder(AVCodecContext* ctx) {
    // 1. 低延迟标志
    ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    ctx->flags2 |= AV_CODEC_FLAG2_FAST;
    
    // 2. 禁用 B 帧参考（减少延迟）
    ctx->has_b_frames = 0;
    
    // 3. 单线程解码（减少缓冲）
    ctx->thread_count = 1;
    
    // 4. 硬件加速（d3d11va）
    ctx->hw_device_ctx = createD3D11Device();
}

// 低延迟拉流配置
void setupLowLatencyInput(AVFormatContext* ctx) {
    AVDictionary* opts = nullptr;
    av_dict_set(&opts, "fflags", "nobuffer", 0);        // 不缓冲
    av_dict_set(&opts, "flags", "low_delay", 0);        // 低延迟
    av_dict_set(&opts, "probesize", "32", 0);           // 最小探测
    av_dict_set(&opts, "analyzeduration", "0", 0);      // 不分析
}
```

### 2.5 最终推荐：FFmpeg + Qt

**为什么选 FFmpeg 而不是 GStreamer？**

| 因素 | GStreamer | FFmpeg |
|------|-----------|--------|
| WebRTC 支持 | webrtcbin 复杂，Java 黑屏 | WHIP/WHEP 简单直接 |
| API 复杂度 | 管线/元素/Pad/探针 | 简单的解码→滤镜→编码 |
| 混沌之眼参考 | 不使用 | ✅ 使用 |
| 社区资源 | 中等 | 丰富（StackOverflow） |
| 调试难度 | 高 | 中 |

### 2.6 推荐方案：FFmpeg + Qt

**理由：**
1. **已有经验** - 当前 Java 版已使用 GStreamer，管线设计可复用
2. **精确帧控制** - C++ 直接操作 GstBuffer，无 JNI 开销
3. **硬件加速完善** - d3d11videosink、nvh264dec 成熟稳定
4. **色彩调节原生** - videobalance、gamma 元素 GPU 加速
5. **Qt 集成** - qml6glsink / Qt GStreamer 插件成熟

---

## 三、架构设计

### 3.1 整体架构（FFmpeg 版）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Qt Quick / QML UI                             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────────────┐  │
│  │ 实时流    │ │ 抓拍列表  │ │ 慢放播放  │ │ 设置面板                  │  │
│  │ VideoItem│ │ GridView │ │ SlowMo   │ │ 色度调节 / FPS / 分辨率    │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────────┬──────────────────┘  │
│       │            │            │                 │                     │
├───────┴────────────┴────────────┴─────────────────┴─────────────────────┤
│                          C++ Backend Layer                              │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                      VideoEngine (单例)                            │ │
│  │                                                                    │ │
│  │  ┌─────────────────────────────────────────────────────────────┐  │ │
│  │  │                    FFmpeg 处理流水线                         │  │ │
│  │  │                                                             │  │ │
│  │  │   WebRTC ──→ 解码 ──→ ⭐色度调节 ──→ tee ──────────────────  │  │ │
│  │  │   (WHIP)    (nvdec)   (eq filter)      │                    │  │ │
│  │  │                                        │                    │  │ │
│  │  │                          ┌─────────────┼─────────────┐      │  │ │
│  │  │                          ▼             ▼             ▼      │  │ │
│  │  │                     [显示分支]    [抓拍分支]    [慢放录制]   │  │ │
│  │  │                     D3D11 渲染    压缩帧环形    H.264 编码   │  │ │
│  │  │                          │        缓冲→MP4         │        │  │ │
│  │  │                          │             │           │        │  │ │
│  │  │                          ▼             ▼           ▼        │  │ │
│  │  │                       屏幕显示    captures/     slowmo/     │  │ │
│  │  │                                   xxx.mp4      xxx.mp4      │  │ │
│  │  └─────────────────────────────────────────────────────────────┘  │ │
│  │                                                                    │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐   │ │
│  │  │ StreamMgr   │  │ CaptureMgr  │  │ SlowMoPlayer            │   │ │
│  │  │ 实时流管理   │  │ 抓拍管理    │  │ 慢放播放+慢放抓拍        │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘   │ │
│  │                                                                    │ │
│  │  ┌─────────────────────────────────────────────────────────────┐  │ │
│  │  │ ColorAdjuster - 色度调节器（影响所有下游数据）               │  │ │
│  │  │   brightness / contrast / saturation / gamma                │  │ │
│  │  └─────────────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                          Platform Layer                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────────────────┐  │
│  │ D3D11    │ │ NVDEC    │ │ 网络层   │ │ 文件系统                   │  │
│  │ 渲染     │ │ 解码     │ │ WebSocket│ │ MP4/JPEG/配置存储          │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘

⭐ 核心设计：色度调节在 tee 分流之前，确保所有分支都带有色度效果
```

### 3.2 数据流详解

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         完整数据流                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  WebRTC Stream (H.264)                                                  │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ FFmpeg 解码 (nvdec/d3d11va 硬解)                                 │   │
│  └──────────────────────────┬──────────────────────────────────────┘   │
│                             │                                           │
│                             ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ ⭐ 色度调节 (FFmpeg eq filter)                                   │   │
│  │    brightness: -1.0 ~ 1.0                                       │   │
│  │    contrast:    0.0 ~ 2.0                                       │   │
│  │    saturation:  0.0 ~ 3.0                                       │   │
│  │    gamma:       0.1 ~ 10.0                                      │   │
│  └──────────────────────────┬──────────────────────────────────────┘   │
│                             │                                           │
│              ┌──────────────┼──────────────┬───────────────┐           │
│              ▼              ▼              ▼               ▼           │
│         ┌─────────┐   ┌─────────┐   ┌─────────────┐  ┌──────────┐     │
│         │ 显示    │   │ 抓拍    │   │ 慢放录制    │  │ 慢放回放  │     │
│         │ D3D11   │   │ 前后60帧│   │ 持续5分钟   │  │ 从磁盘    │     │
│         └────┬────┘   └────┬────┘   └──────┬──────┘  └─────┬────┘     │
│              │              │              │               │           │
│              ▼              ▼              ▼               ▼           │
│           屏幕         MP4文件         MP4文件          帧显示         │
│                        (带色度)       (带色度)         (带色度)        │
│                            │                               │           │
│                            ▼                               ▼           │
│                       抓拍查看                         慢放抓拍         │
│                       (带色度)                         (带色度)        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 核心模块设计

#### 3.2.1 精确抓拍方案（低内存版）

**核心思路：视频片段录制 + 精确帧索引**

**Q: 视频片段存储，上下帧查看方便吗？**

**A: 非常方便**，比 JPEG 序列更优：

| 方面 | JPEG 序列 (当前) | MP4 视频片段 (新) |
|------|-----------------|-------------------|
| 存储大小 | 120 × 300KB = 36MB | 4秒 × 2MB/s = 8MB |
| 定位速度 | 需读取文件列表 | 直接 seek 到帧 |
| 前后帧跳转 | 重新加载 JPEG | 内存中直接渲染 |
| 关键帧索引 | 无（每帧都是独立） | 有（I/P/B 帧结构） |
| 解码开销 | 每帧 JPEG 解码 | 只解码 GOP 内增量 |

**关键技术：GOP 结构优化**

```
视频片段 GOP 设计（针对帧浏览优化）：

  GOP Size = 15（每 15 帧一个关键帧 @ 60fps = 0.25秒）
  
  I P P P P P P P P P P P P P P I P P P P ...
  ↑                               ↑
  关键帧                          关键帧
  
  向前跳转：直接解码下一帧（快）
  向后跳转：seek 到前一个关键帧，解码到目标帧（最多解码 15 帧）
  
  优化：生成关键帧索引表，向后跳转时只解码必要帧
```

**帧浏览实现代码：**

```cpp
// 高效帧浏览 - 利用 GOP 结构
class FrameBrowser {
public:
    void seekToFrame(int targetFrame) {
        int currentGOP = m_currentFrame / GOP_SIZE;
        int targetGOP = targetFrame / GOP_SIZE;
        
        if (targetGOP == currentGOP && targetFrame > m_currentFrame) {
            // 同 GOP 向前：逐帧解码（快）
            while (m_currentFrame < targetFrame) {
                decodeNextFrame();
                m_currentFrame++;
            }
        } else {
            // 不同 GOP 或向后：seek 到关键帧再解码
            int keyframePos = targetGOP * GOP_SIZE;
            seekToKeyframe(keyframePos);
            while (m_currentFrame < targetFrame) {
                decodeNextFrame();
                m_currentFrame++;
            }
        }
        displayFrame();
    }
    
private:
    static constexpr int GOP_SIZE = 15;  // 0.25秒 @ 60fps
};
```

```
┌─────────────────────────────────────────────────────────────┐
│                    GStreamer Pipeline                       │
│                                                             │
│  webrtcbin → decodebin → tee ─┬─→ [显示] d3d11videosink    │
│                               │                             │
│                               └─→ [录制] splitmuxsink       │
│                                    ↓                        │
│                             capture_001.mp4 (2秒片段)       │
│                             capture_002.mp4                 │
│                             ...                             │
└─────────────────────────────────────────────────────────────┘

内存占用计算：
- 实时环形缓冲：120 帧 × 压缩帧 ≈ 50 MB（仅用于前帧捕获）
- 100 个视频片段索引：100 × 1 KB = 100 KB
- 当前查看的解码缓存：10 帧 × 10 MB = 100 MB
- 总计：约 150-200 MB ✅
```

```cpp
// ⭐ 方案1：splitmuxsink 持续录制 + 精确切片
class CaptureManager : public QObject {
    Q_OBJECT
public:
    void init() {
        // 持续录制到环形视频文件（覆盖旧的）
        // splitmuxsink 自动按时间/大小分割
        m_recordPipeline = R"(
            ... ! queue ! x264enc tune=zerolatency ! h264parse !
            splitmuxsink location=ring_%05d.mp4 max-size-time=2000000000
        )";
        // max-size-time = 2秒 = 2,000,000,000 纳秒
    }
    
    // 抓拍触发
    void triggerCapture() {
        // 1. 记录当前精确帧 ID 和时间戳
        m_captureFrameId = m_currentFrameId.load();
        m_capturePts = m_currentPts.load();
        
        // 2. 标记需要保存的视频片段范围
        // 前2秒 + 后2秒 = 共4秒视频
        m_captureStartPts = m_capturePts - 2 * GST_SECOND;
        m_captureEndPts = m_capturePts + 2 * GST_SECOND;
        
        // 3. 2秒后，从环形录制中提取片段
        QTimer::singleShot(2000, this, [this]() {
            extractCaptureSegment();
        });
    }
    
private:
    // 从环形录制中提取精确片段
    void extractCaptureSegment() {
        QString outputPath = QString("captures/capture_%1.mp4").arg(m_captureId++);
        
        // 使用 GStreamer 精确切割（基于 PTS）
        QString pipeline = QString(
            "filesrc location=%1 ! qtdemux ! h264parse ! "
            "video/x-h264,stream-format=byte-stream ! "
            "splitmuxsink location=%2 max-size-time=4000000000"
        ).arg(m_ringFile, outputPath);
        
        // 或使用 ffmpeg 精确 seek
        // ffmpeg -ss START -i ring.mp4 -t 4 -c copy capture_X.mp4
        
        emit captureReady(outputPath, m_captureFrameId);
    }
    
    std::atomic<guint64> m_currentFrameId{0};
    std::atomic<GstClockTime> m_currentPts{0};
};

// ⭐ 方案2：按需编码（更精确但稍复杂）
class OnDemandCaptureManager : public QObject {
public:
    void init() {
        // 只保留压缩帧的环形缓冲（H.264 NAL 单元）
        // 不解码，直接存 encoded frame
        m_compressedRing.resize(300);  // 5秒 @ 60fps
        // 每帧约 20-50 KB（压缩后）
        // 300 × 50 KB = 15 MB 内存 ✅
    }
    
    // Pad Probe 捕获压缩帧（在解码前）
    static GstPadProbeReturn onEncodedFrame(GstPad* pad, GstPadProbeInfo* info, gpointer data) {
        auto* self = static_cast<OnDemandCaptureManager*>(data);
        GstBuffer* buffer = GST_PAD_PROBE_INFO_BUFFER(info);
        
        // 存储压缩帧（约 20-50 KB）
        CompressedFrame frame;
        frame.pts = GST_BUFFER_PTS(buffer);
        frame.frameId = self->m_frameCounter++;
        frame.data = extractBufferData(buffer);  // 拷贝压缩数据
        frame.isKeyframe = !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
        
        self->m_compressedRing.push(std::move(frame));
        return GST_PAD_PROBE_OK;
    }
    
    void triggerCapture() {
        guint64 captureFrame = m_frameCounter.load();
        
        // 从环形缓冲中提取前60帧
        std::vector<CompressedFrame> preFrames;
        m_compressedRing.extractBefore(captureFrame, 60, preFrames);
        
        // 等待后60帧，然后保存
        m_pendingCapture = {captureFrame, std::move(preFrames)};
        m_postFramesNeeded = 60;
    }
    
    void saveCapture() {
        // 将压缩帧直接封装成 MP4（无需重新编码）
        // 使用 mp4mux 或直接写 MP4 容器
        QString path = QString("captures/capture_%1.mp4").arg(m_captureId++);
        
        GstElement* muxer = gst_element_factory_make("mp4mux", nullptr);
        // ... 写入所有帧 ...
        
        emit captureReady(path);
    }
    
private:
    RingBuffer<CompressedFrame> m_compressedRing;  // 15-30 MB
    std::atomic<guint64> m_frameCounter{0};
};
```

#### 3.2.2 色彩调节方案

```cpp
// ⭐ GPU 加速色彩调节 - 零 CPU 开销
class ColorAdjuster : public QObject {
    Q_OBJECT
    Q_PROPERTY(double brightness READ brightness WRITE setBrightness NOTIFY brightnessChanged)
    Q_PROPERTY(double contrast READ contrast WRITE setContrast NOTIFY contrastChanged)
    Q_PROPERTY(double saturation READ saturation WRITE setSaturation NOTIFY saturationChanged)
    Q_PROPERTY(double hue READ hue WRITE setHue NOTIFY hueChanged)
    Q_PROPERTY(double gamma READ gamma WRITE setGamma NOTIFY gammaChanged)

public:
    void setBrightness(double value) {
        // ⭐ 直接设置 GStreamer 元素属性 - GPU 处理
        g_object_set(m_videoBalance, "brightness", value, nullptr);
        emit brightnessChanged();
    }
    
    void setGamma(double value) {
        g_object_set(m_gamma, "gamma", value, nullptr);
        emit gammaChanged();
    }
    
private:
    GstElement* m_videoBalance;  // GStreamer 内置元素
    GstElement* m_gamma;         // GStreamer gamma 元素
};
```

#### 3.2.3 多屏支持

```cpp
// ⭐ 多屏管理 - 共享解码，多渲染
class MultiScreenManager : public QObject {
    Q_OBJECT
public:
    void init() {
        // 单一解码器，多个渲染分支
        // 避免多路解码占用 CPU
        m_pipeline = R"(
            webrtcbin ! rtph264depay ! h264parse ! tee name=decoded
            decoded. ! queue ! nvh264dec ! tee name=display
                display. ! queue ! d3d11videosink name=sink0  # 主屏
                display. ! queue ! d3d11videosink name=sink1  # 副屏1
                display. ! queue ! d3d11videosink name=sink2  # 副屏2
            decoded. ! queue ! appsink name=capture           # 抓拍
        )";
    }
    
    // 动态添加/移除屏幕
    Q_INVOKABLE void addScreen(int screenIndex, QQuickItem* videoItem) {
        GstElement* sink = createD3D11Sink(screenIndex);
        
        // 获取对应屏幕的 GPU 适配器
        QScreen* screen = QGuiApplication::screens().at(screenIndex);
        QString adapter = getD3D11Adapter(screen);
        g_object_set(sink, "adapter", adapter.toUtf8().data(), nullptr);
        
        // 链接到 display tee
        linkToTee(m_displayTee, sink);
        
        // 绑定到 QML VideoItem
        bindSinkToQml(sink, videoItem);
        
        m_sinks[screenIndex] = sink;
    }
    
    Q_INVOKABLE void removeScreen(int screenIndex) {
        if (m_sinks.contains(screenIndex)) {
            unlinkFromTee(m_displayTee, m_sinks[screenIndex]);
            gst_object_unref(m_sinks[screenIndex]);
            m_sinks.remove(screenIndex);
        }
    }
    
private:
    GstElement* m_pipeline = nullptr;
    GstElement* m_displayTee = nullptr;
    QMap<int, GstElement*> m_sinks;
};
```

#### 3.2.4 慢放功能需求分析

**两种不同场景：**

| 场景 | 时长 | 帧数 | 存储 | 用途 |
|------|------|------|------|------|
| **实时抓拍** | 2 秒 | 120 帧 | 内存环 → MP4 | 快速回看 |
| **慢放录制** | 5 分钟 | 18000 帧 | 直接 MP4 | 详细分析 |

**慢放中抓拍需求：**
- 播放到某一帧时，可以"再抓拍"
- 截取当前帧保存为 JPEG/PNG
- 或标记该帧位置，后续导出

```
┌─────────────────────────────────────────────────────────────┐
│                    慢放功能架构                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. 慢放录制（5分钟）                                        │
│     实时流 → FFmpeg 编码 → slowmo_001.mp4 (持续录制)        │
│     大小：5分钟 × 60fps × ~50KB/帧 ≈ 900 MB                 │
│     或 H.264 压缩：5分钟 × 2MB/s ≈ 600 MB                   │
│                                                             │
│  2. 慢放回放                                                 │
│     slowmo_001.mp4 → FFmpeg 解码 → 变速播放                 │
│     支持：0.1x ~ 2x 变速、帧步进、seek                       │
│                                                             │
│  3. 慢放中抓拍                                               │
│     播放到某帧 → 用户点击"抓拍"                              │
│         ↓                                                   │
│     当前 AVFrame → 保存 JPEG/PNG                            │
│     或：标记帧号 → 后续批量导出                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### 3.2.5 慢放播放器（支持慢放中抓拍）

```cpp
// ⭐ 高性能慢放 - 变速 + 帧抓拍（FFmpeg 实现）
class SlowMoPlayer : public QObject {
    Q_OBJECT
    Q_PROPERTY(double speed READ speed WRITE setSpeed NOTIFY speedChanged)
    Q_PROPERTY(int currentFrame READ currentFrame NOTIFY frameChanged)
    Q_PROPERTY(int totalFrames READ totalFrames CONSTANT)
    Q_PROPERTY(bool playing READ isPlaying NOTIFY playingChanged)
    
public:
    // 加载慢放视频（可能 5 分钟）
    void load(const QString& mp4Path) {
        m_path = mp4Path;
        
        // 打开视频文件
        avformat_open_input(&m_formatCtx, mp4Path.toUtf8().data(), nullptr, nullptr);
        avformat_find_stream_info(m_formatCtx, nullptr);
        
        // 找到视频流
        m_videoStreamIdx = av_find_best_stream(m_formatCtx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        
        // 创建解码器
        AVCodecParameters* codecPar = m_formatCtx->streams[m_videoStreamIdx]->codecpar;
        const AVCodec* codec = avcodec_find_decoder(codecPar->codec_id);
        m_codecCtx = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(m_codecCtx, codecPar);
        avcodec_open2(m_codecCtx, codec, nullptr);
        
        // 计算总帧数
        m_totalFrames = m_formatCtx->streams[m_videoStreamIdx]->nb_frames;
        m_fps = av_q2d(m_formatCtx->streams[m_videoStreamIdx]->r_frame_rate);
        
        emit totalFramesChanged();
    }
    
    // ⭐ 慢放中抓拍 - 保存当前帧
    Q_INVOKABLE QString captureCurrentFrame() {
        if (!m_currentFrame) return QString();
        
        // 生成文件名
        QString filename = QString("slowmo_capture_%1_%2.jpg")
            .arg(QDateTime::currentDateTime().toString("yyyyMMdd_HHmmss"))
            .arg(m_currentFrameIdx);
        QString path = "captures/" + filename;
        
        // 转换为 RGB 并保存 JPEG
        SwsContext* swsCtx = sws_getContext(
            m_currentFrame->width, m_currentFrame->height, (AVPixelFormat)m_currentFrame->format,
            m_currentFrame->width, m_currentFrame->height, AV_PIX_FMT_RGB24,
            SWS_BILINEAR, nullptr, nullptr, nullptr);
        
        AVFrame* rgbFrame = av_frame_alloc();
        rgbFrame->width = m_currentFrame->width;
        rgbFrame->height = m_currentFrame->height;
        rgbFrame->format = AV_PIX_FMT_RGB24;
        av_frame_get_buffer(rgbFrame, 0);
        
        sws_scale(swsCtx, m_currentFrame->data, m_currentFrame->linesize, 0,
                  m_currentFrame->height, rgbFrame->data, rgbFrame->linesize);
        
        // 编码为 JPEG
        saveJpeg(rgbFrame, path);
        
        av_frame_free(&rgbFrame);
        sws_freeContext(swsCtx);
        
        emit frameCaptured(path, m_currentFrameIdx);
        return path;
    }
    
    // ⭐ 批量标记帧（后续导出）
    Q_INVOKABLE void markFrame() {
        m_markedFrames.append(m_currentFrameIdx);
        emit frameMarked(m_currentFrameIdx);
    }
    
    // ⭐ 导出所有标记帧
    Q_INVOKABLE void exportMarkedFrames(const QString& outputDir) {
        for (int frameIdx : m_markedFrames) {
            seekToFrame(frameIdx);
            decodeCurrentFrame();
            QString path = QString("%1/frame_%2.jpg").arg(outputDir).arg(frameIdx, 6, 10, QChar('0'));
            saveCurrentFrameAsJpeg(path);
        }
        emit exportComplete(m_markedFrames.size());
    }
    
    // 变速播放 (0.1x - 2.0x)
    void setSpeed(double speed) {
        m_speed = qBound(0.1, speed, 2.0);
        // 调整播放定时器间隔
        int interval = static_cast<int>(1000.0 / (m_fps * m_speed));
        m_playTimer->setInterval(interval);
        emit speedChanged();
    }
    
    // 播放/暂停
    Q_INVOKABLE void togglePlay() {
        if (m_playing) {
            m_playTimer->stop();
        } else {
            m_playTimer->start();
        }
        m_playing = !m_playing;
        emit playingChanged();
    }
    
    // 单帧步进
    Q_INVOKABLE void stepForward() {
        seekToFrame(m_currentFrameIdx + 1);
        decodeCurrentFrame();
        emit frameChanged();
    }
    
    Q_INVOKABLE void stepBackward() {
        seekToFrame(qMax(0, m_currentFrameIdx - 1));
        decodeCurrentFrame();
        emit frameChanged();
    }
    
    // 跳转到指定帧
    Q_INVOKABLE void seekToFrame(int frameIdx) {
        frameIdx = qBound(0, frameIdx, m_totalFrames - 1);
        
        // 计算时间戳
        AVStream* stream = m_formatCtx->streams[m_videoStreamIdx];
        int64_t timestamp = av_rescale_q(frameIdx, av_inv_q(stream->r_frame_rate), stream->time_base);
        
        // 精确 seek
        av_seek_frame(m_formatCtx, m_videoStreamIdx, timestamp, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(m_codecCtx);
        
        // 解码到目标帧
        while (m_currentFrameIdx < frameIdx) {
            decodeNextFrame();
        }
        
        emit frameChanged();
    }
    
signals:
    void frameChanged();
    void speedChanged();
    void playingChanged();
    void totalFramesChanged();
    void frameCaptured(const QString& path, int frameIdx);
    void frameMarked(int frameIdx);
    void exportComplete(int count);
    
private:
    void decodeNextFrame() {
        AVPacket* pkt = av_packet_alloc();
        while (av_read_frame(m_formatCtx, pkt) >= 0) {
            if (pkt->stream_index == m_videoStreamIdx) {
                avcodec_send_packet(m_codecCtx, pkt);
                if (avcodec_receive_frame(m_codecCtx, m_currentFrame) == 0) {
                    m_currentFrameIdx++;
                    av_packet_unref(pkt);
                    break;
                }
            }
            av_packet_unref(pkt);
        }
        av_packet_free(&pkt);
    }
    
    QString m_path;
    AVFormatContext* m_formatCtx = nullptr;
    AVCodecContext* m_codecCtx = nullptr;
    AVFrame* m_currentFrame = nullptr;
    int m_videoStreamIdx = -1;
    int m_totalFrames = 0;
    int m_currentFrameIdx = 0;
    double m_fps = 60.0;
    double m_speed = 1.0;
    bool m_playing = false;
    QTimer* m_playTimer = nullptr;
    QVector<int> m_markedFrames;  // 标记的帧列表
};
```

---

## 四、关键改进点

### 4.1 抓拍精度提升

| 方面 | JavaFX 现状 | Qt C++ 方案 | 提升 |
|------|------------|-------------|------|
| 帧索引获取 | JNI 回调，有延迟 | Pad Probe 直接获取 | **零延迟** |
| 内存环写入 | Java 堆 + GC | 预分配堆外内存 | **零拷贝** |
| 抓拍触发 | Java 事件队列 | 原子变量标记 | **微秒级** |
| 显示同步 | 偏移补偿（猜测） | PTS 时间戳精确匹配 | **帧精确** |

### 4.2 稳定性提升

```cpp
// ⭐ 抗马赛克策略
void setupDecoder(GstElement* decoder) {
    // 1. 丢弃损坏帧，不显示
    g_object_set(decoder, "discard-corrupted-frames", TRUE, nullptr);
    
    // 2. 错误恢复
    g_object_set(decoder, "max-errors", 100, nullptr);
    
    // 3. 低延迟模式
    g_object_set(decoder, "low-latency", TRUE, nullptr);
    
    // 4. 硬件解码优先
    // nvh264dec > d3d11h264dec > avdec_h264
}

// ⭐ 网络抖动缓冲
void setupJitterBuffer(GstElement* rtpbin) {
    g_object_set(rtpbin,
        "latency", 50,           // 50ms 缓冲
        "drop-on-latency", TRUE, // 超时丢弃
        nullptr);
}
```

### 4.3 性能对比预估

| 指标 | JavaFX 现状 | Qt C++ 预期 |
|------|------------|-------------|
| 启动时间 | 3-5 秒 | < 1 秒 |
| 内存占用 (空载) | 300-400 MB | 100-150 MB |
| 内存占用 (100抓拍满载) | 500-800 MB | **400-500 MB** |
| CPU (单路 1080p60) | 15-25% | 5-10% |
| GPU 抓拍延迟 | 400-500 帧 | 0-2 帧 |
| 色彩调节响应 | 50-100ms | < 16ms |

### 4.4 内存方案详解

```
┌────────────────────────────────────────────────────────────────┐
│                    内存占用分析 (满载 100 抓拍)                 │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ❌ 错误方案：原始帧全内存                                      │
│     100 × 120 帧 × 10.5 MB = 126 GB  💥                        │
│                                                                │
│  ❌ JPEG 内存方案：                                             │
│     100 × 120 帧 × 300 KB = 3.6 GB   💥                        │
│                                                                │
│  ✅ 正确方案：压缩帧环形缓冲 + 视频片段磁盘存储                  │
│                                                                │
│     实时组件：                                                  │
│     ├─ 压缩帧环形缓冲 (解码前)     300帧 × 50KB = 15 MB        │
│     ├─ GPU 显示缓冲               3帧 × 10MB = 30 MB           │
│     └─ GStreamer 内部缓冲                     ≈ 50 MB          │
│                                                                │
│     抓拍存储：                                                  │
│     ├─ 100 个 MP4 索引/元数据     100 × 10KB = 1 MB            │
│     └─ 当前查看的解码缓存         10帧 × 10MB = 100 MB         │
│                                                                │
│     磁盘存储（不占内存）：                                       │
│     └─ 100 × 4秒 MP4 × 2MB/秒 = 800 MB 磁盘                    │
│                                                                │
│     总内存：15 + 30 + 50 + 1 + 100 ≈ 200 MB                    │
│     + Qt/系统开销 ≈ 200 MB                                     │
│     ─────────────────────────────                              │
│     总计：约 400-500 MB ✅                                      │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**关键技术点：**

1. **压缩帧环形缓冲** - 在解码器前截取 H.264 NAL 单元
   - 比原始帧小 200-500 倍
   - 用于捕获"前帧"（抓拍前的帧）

2. **按需解码** - 只有查看时才解码
   - 最多同时解码 1 个抓拍的 10 帧（滑动窗口）
   - 滚动/切换时释放旧缓存

3. **视频片段存储** - 抓拍结果存 MP4
   - 直接封装压缩帧，无需重编码
   - 比 120 张 JPEG 更小更快

### 4.5 抓拍查看器低内存实现

```cpp
// ⭐ 按需解码播放器 - 只解码可见帧
class CaptureViewer : public QObject {
    Q_OBJECT
public:
    // 加载抓拍片段（只读取索引，不解码）
    void loadCapture(const QString& mp4Path) {
        m_currentPath = mp4Path;
        
        // 只解析视频元信息，不加载帧数据
        m_demuxer = createDemuxer(mp4Path);
        m_totalFrames = getTotalFrames(m_demuxer);
        m_keyframeIndex = buildKeyframeIndex(m_demuxer);  // 关键帧位置表
        
        // 预解码当前帧和前后各2帧（共5帧）
        seekToFrame(m_totalFrames / 2);  // 默认显示中间帧（事件帧）
    }
    
    // 跳转到指定帧（按需解码）
    void seekToFrame(int frameIndex) {
        frameIndex = qBound(0, frameIndex, m_totalFrames - 1);
        
        // 计算需要解码的帧范围（滑动窗口）
        int windowStart = qMax(0, frameIndex - 2);
        int windowEnd = qMin(m_totalFrames - 1, frameIndex + 2);
        
        // 释放窗口外的帧（控制内存）
        for (auto it = m_decodedFrames.begin(); it != m_decodedFrames.end(); ) {
            if (it.key() < windowStart || it.key() > windowEnd) {
                it = m_decodedFrames.erase(it);  // 释放内存
            } else {
                ++it;
            }
        }
        
        // 解码窗口内缺失的帧
        for (int i = windowStart; i <= windowEnd; ++i) {
            if (!m_decodedFrames.contains(i)) {
                m_decodedFrames[i] = decodeFrame(i);
            }
        }
        
        m_currentFrame = frameIndex;
        emit frameChanged(m_decodedFrames[frameIndex]);
    }
    
    // 下一帧
    void nextFrame() { seekToFrame(m_currentFrame + 1); }
    
    // 上一帧
    void prevFrame() { seekToFrame(m_currentFrame - 1); }
    
private:
    // 高效解码单帧（seek 到最近关键帧，解码到目标帧）
    QImage decodeFrame(int frameIndex) {
        // 找到最近的关键帧
        int nearestKeyframe = findNearestKeyframe(frameIndex);
        
        // 从关键帧开始解码
        seekDemuxer(m_demuxer, nearestKeyframe);
        
        QImage result;
        for (int i = nearestKeyframe; i <= frameIndex; ++i) {
            result = decodeNextFrame(m_decoder);
            if (i < frameIndex) {
                // 中间帧不需要，直接丢弃
            }
        }
        return result;
    }
    
    QString m_currentPath;
    int m_totalFrames = 0;
    int m_currentFrame = 0;
    QVector<int> m_keyframeIndex;          // 关键帧位置
    QMap<int, QImage> m_decodedFrames;     // 解码缓存（最多5帧 = 50MB）
    GstElement* m_demuxer = nullptr;
    GstElement* m_decoder = nullptr;
};
```

### 4.6 与 Java 版架构对比

```
┌─────────────────────────────────────────────────────────────────┐
│                    Java 版架构（当前）                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GStreamer ──JNI──→ Java 回调 ──→ JavaFX 渲染                   │
│       ↓                                                         │
│  jpegenc ──→ multifilesink ──→ 磁盘 JPEG                        │
│       ↓                                                         │
│  [问题] JPEG 编码慢，帧差 400-500                                │
│  [问题] 查看时加载 120 张 JPEG，I/O 密集                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    C++ 版架构（新）                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GStreamer ──直接──→ Qt 渲染（无 JNI）                          │
│       ↓                                                         │
│  [解码前] Pad Probe 截取压缩帧 ──→ 环形缓冲（15MB）              │
│       ↓                                                         │
│  触发抓拍 ──→ 封装 MP4（无重编码）──→ 磁盘                       │
│       ↓                                                         │
│  查看时 ──→ 按需解码 5 帧窗口（50MB）                            │
│                                                                 │
│  [优势] 帧 ID 精确，抓拍即时                                     │
│  [优势] MP4 比 120 张 JPEG 更小                                  │
│  [优势] 按需解码，内存可控                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.7 GStreamer 管线对比

```bash
# Java 版（当前）- JPEG 路径延迟大
webrtcbin ! decodebin ! tee name=t
  t. ! queue ! videobalance ! d3d11videosink        # 显示
  t. ! queue ! jpegenc ! multifilesink              # 抓拍（慢，有延迟）

# C++ 版（新）- 压缩帧直接捕获
webrtcbin ! rtph264depay ! h264parse ! tee name=t
  t. ! queue ! avdec_h264 ! videobalance ! d3d11videosink   # 显示
  t. ! queue ! appsink name=capture_sink                     # 抓拍（快，无延迟）
                ↓
        [C++ 代码接收压缩帧，存入环形缓冲]
        [触发时直接封装 MP4，帧 ID 精确]
```

---

## 五、依赖与编译配置

### 5.1 依赖库版本

| 依赖 | 推荐版本 | 说明 |
|------|---------|------|
| Qt | 6.5+ | Qt Quick, Qt Multimedia |
| GStreamer | 1.22+ | MSVC 构建版本 |
| gst-plugins-bad | 1.22+ | 包含 d3d11 插件 |
| OpenSSL | 3.0+ | WebRTC DTLS |

### 5.2 CMakeLists.txt 示例

```cmake
cmake_minimum_required(VERSION 3.20)
project(AcardQt VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

# ========== Qt ==========
find_package(Qt6 REQUIRED COMPONENTS
    Core
    Quick
    QuickControls2
    Multimedia
    Network
    WebSockets
)

# ========== GStreamer ==========
# Windows: 使用 pkg-config 或手动设置路径
if(WIN32)
    set(GSTREAMER_ROOT "C:/gstreamer/1.0/msvc_x86_64")
    set(ENV{PKG_CONFIG_PATH} "${GSTREAMER_ROOT}/lib/pkgconfig")
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(GST REQUIRED
    gstreamer-1.0>=1.22
    gstreamer-video-1.0
    gstreamer-app-1.0
    gstreamer-webrtc-1.0
    gstreamer-sdp-1.0
)

# ========== 源文件 ==========
set(SOURCES
    src/main.cpp
    src/core/VideoEngine.cpp
    src/core/GstPipeline.cpp
    src/core/StreamManager.cpp
    src/core/CaptureManager.cpp
    src/core/SlowMoPlayer.cpp
    src/core/ColorAdjuster.cpp
    src/core/MultiScreenManager.cpp
    src/network/WebSocketClient.cpp
    src/network/SignalingClient.cpp
    src/storage/FrameRing.cpp
    src/storage/ConfigStore.cpp
    src/ui/VideoItem.cpp
    src/ui/CaptureViewer.cpp
)

set(HEADERS
    src/core/VideoEngine.h
    src/core/GstPipeline.h
    # ... 其他头文件
)

set(QML_FILES
    qml/main.qml
    qml/components/VideoView.qml
    qml/components/CaptureGrid.qml
    qml/components/SlowMoPlayer.qml
    qml/components/SettingsPanel.qml
)

# ========== 可执行文件 ==========
qt_add_executable(AcardQt
    ${SOURCES}
    ${HEADERS}
)

qt_add_qml_module(AcardQt
    URI AcardQt
    VERSION 1.0
    QML_FILES ${QML_FILES}
    RESOURCES
        resources/icons/logo.png
        resources/fonts/SourceHanSans.ttf
)

# ========== 链接库 ==========
target_include_directories(AcardQt PRIVATE
    ${GST_INCLUDE_DIRS}
    src
)

target_link_libraries(AcardQt PRIVATE
    Qt6::Core
    Qt6::Quick
    Qt6::QuickControls2
    Qt6::Multimedia
    Qt6::Network
    Qt6::WebSockets
    ${GST_LIBRARIES}
)

# Windows 特定设置
if(WIN32)
    # 复制 GStreamer DLL 到输出目录
    add_custom_command(TARGET AcardQt POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${GSTREAMER_ROOT}/bin"
            "$<TARGET_FILE_DIR:AcardQt>"
    )
    
    # 设置为 Windows 应用（无控制台）
    set_target_properties(AcardQt PROPERTIES
        WIN32_EXECUTABLE TRUE
    )
endif()

# ========== 安装 ==========
install(TARGETS AcardQt
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
```

### 5.3 Windows 构建脚本

```powershell
# build.ps1

# 设置 Qt 和 GStreamer 路径
$env:Qt6_DIR = "C:\Qt\6.5.3\msvc2019_64"
$env:GSTREAMER_ROOT = "C:\gstreamer\1.0\msvc_x86_64"
$env:PATH = "$env:Qt6_DIR\bin;$env:GSTREAMER_ROOT\bin;$env:PATH"

# 创建构建目录
New-Item -ItemType Directory -Force -Path build
Set-Location build

# CMake 配置
cmake .. -G "Visual Studio 17 2022" -A x64 `
    -DCMAKE_PREFIX_PATH="$env:Qt6_DIR" `
    -DCMAKE_BUILD_TYPE=Release

# 编译
cmake --build . --config Release --parallel

# 部署 Qt 依赖
& "$env:Qt6_DIR\bin\windeployqt.exe" --qmldir ..\qml Release\AcardQt.exe

Write-Host "构建完成: build\Release\AcardQt.exe"
```

---

## 六、项目结构

```
AcardQt/
├── CMakeLists.txt
├── build.ps1                      # Windows 构建脚本
├── src/
│   ├── main.cpp
│   ├── core/
│   │   ├── VideoEngine.h/cpp      # 核心引擎单例
│   │   ├── GstPipeline.h/cpp      # GStreamer 管线封装
│   │   ├── StreamManager.h/cpp    # 实时流管理
│   │   ├── CaptureManager.h/cpp   # 抓拍管理（压缩帧环形缓冲）
│   │   ├── SlowMoPlayer.h/cpp     # 慢放播放器
│   │   ├── ColorAdjuster.h/cpp    # 色彩调节
│   │   └── MultiScreenManager.h/cpp # 多屏管理
│   ├── network/
│   │   ├── WebSocketClient.h/cpp  # WebSocket 通信
│   │   ├── SignalingClient.h/cpp  # WebRTC 信令
│   │   └── ConfigSync.h/cpp       # 配置同步
│   ├── storage/
│   │   ├── CompressedFrameRing.h/cpp  # 压缩帧环形缓冲（15MB）
│   │   ├── Mp4Writer.h/cpp        # MP4 封装器（无重编码）
│   │   └── ConfigStore.h/cpp      # 配置存储 (QSettings)
│   ├── ui/
│   │   ├── VideoItem.h/cpp        # QML 视频组件
│   │   ├── CaptureViewer.h/cpp    # 抓拍查看器（按需解码）
│   │   └── CaptureGridModel.h/cpp # 抓拍列表模型
│   └── platform/
│       └── D3D11Helper.h/cpp      # D3D11 工具函数
├── qml/
│   ├── main.qml                   # 主窗口
│   ├── components/
│   │   ├── VideoView.qml          # 实时流视频
│   │   ├── CaptureGrid.qml        # 抓拍网格
│   │   ├── CaptureItemView.qml    # 单个抓拍项
│   │   ├── SlowMoPlayer.qml       # 慢放播放器
│   │   ├── SettingsPanel.qml      # 设置面板
│   │   └── ColorSliders.qml       # 色彩调节滑块
│   └── styles/
│       ├── Theme.qml              # 主题常量
│       └── Controls.qml           # 自定义控件样式
├── resources/
│   ├── resources.qrc
│   ├── icons/
│   └── fonts/
└── tests/
    ├── test_capture.cpp
    ├── test_frame_ring.cpp
    └── test_slowmo.cpp
```

---

## 七、QML UI 示例

### 7.1 主窗口布局

```qml
// qml/main.qml
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import AcardQt

ApplicationWindow {
    id: root
    width: 1920
    height: 1080
    visible: true
    title: "Acard 高速摄像"
    color: "#1a1a2e"
    
    // 全局引擎实例
    VideoEngine { id: engine }
    
    RowLayout {
        anchors.fill: parent
        spacing: 0
        
        // ===== 左侧：实时流 + 控制 =====
        ColumnLayout {
            Layout.preferredWidth: parent.width * 0.6
            Layout.fillHeight: true
            spacing: 10
            
            // 实时视频
            VideoView {
                id: liveView
                Layout.fillWidth: true
                Layout.fillHeight: true
                engine: engine
            }
            
            // 底部控制栏
            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 60
                spacing: 20
                
                // 抓拍按钮
                Button {
                    text: "📸 抓拍"
                    onClicked: engine.capture()
                }
                
                // 色彩调节
                ColorSliders {
                    Layout.fillWidth: true
                    engine: engine
                }
            }
        }
        
        // ===== 右侧：抓拍列表 =====
        CaptureGrid {
            Layout.preferredWidth: parent.width * 0.4
            Layout.fillHeight: true
            model: engine.captureModel
            onItemClicked: (index) => slowMoPopup.open(index)
        }
    }
    
    // 慢放弹窗
    SlowMoPopup {
        id: slowMoPopup
        engine: engine
    }
}
```

### 7.2 慢放播放器

```qml
// qml/components/SlowMoPlayer.qml
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: popup
    width: parent.width * 0.9
    height: parent.height * 0.9
    modal: true
    closePolicy: Popup.CloseOnEscape
    
    property var engine
    property int captureIndex: -1
    
    ColumnLayout {
        anchors.fill: parent
        spacing: 10
        
        // 视频显示区
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            color: "black"
            
            VideoOutput {
                anchors.fill: parent
                source: engine.slowMoPlayer
            }
            
            // 帧号显示
            Text {
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.margins: 10
                text: `帧 ${engine.slowMoPlayer.currentFrame} / ${engine.slowMoPlayer.totalFrames}`
                color: "white"
                font.pixelSize: 18
            }
        }
        
        // 时间轴
        Slider {
            id: timeline
            Layout.fillWidth: true
            from: 0
            to: engine.slowMoPlayer.totalFrames - 1
            value: engine.slowMoPlayer.currentFrame
            onMoved: engine.slowMoPlayer.seekToFrame(value)
        }
        
        // 控制按钮
        RowLayout {
            Layout.fillWidth: true
            spacing: 20
            
            Button { text: "◀◀"; onClicked: engine.slowMoPlayer.stepBackward() }
            Button { text: engine.slowMoPlayer.playing ? "⏸" : "▶"; onClicked: engine.slowMoPlayer.togglePlay() }
            Button { text: "▶▶"; onClicked: engine.slowMoPlayer.stepForward() }
            
            ComboBox {
                model: ["0.1x", "0.25x", "0.5x", "1x", "2x"]
                currentIndex: 2
                onCurrentTextChanged: engine.slowMoPlayer.setSpeed(parseFloat(currentText))
            }
            
            CheckBox { text: "循环"; onCheckedChanged: engine.slowMoPlayer.setLooping(checked) }
            
            Item { Layout.fillWidth: true }
            
            Button { text: "导出 GIF"; onClicked: engine.exportGif(captureIndex) }
            Button { text: "导出 MP4"; onClicked: engine.exportMp4(captureIndex) }
        }
    }
    
    // 键盘快捷键
    Shortcut { sequence: "Left"; onActivated: engine.slowMoPlayer.stepBackward() }
    Shortcut { sequence: "Right"; onActivated: engine.slowMoPlayer.stepForward() }
    Shortcut { sequence: "Space"; onActivated: engine.slowMoPlayer.togglePlay() }
}
```

---

## 八、开发计划

### Phase 1: 核心框架 (2 周)
- [ ] Qt 6.5 + CMake 项目搭建
- [ ] GStreamer 1.22 MSVC 集成
- [ ] 基础 Pipeline 封装
- [ ] D3D11 渲染测试
- [ ] WebRTC 信令连接测试

### Phase 2: 实时流 (2 周)
- [ ] 完整实时流管线 (WebRTC → 显示)
- [ ] 色彩/亮度/对比度/伽马调节 (GPU)
- [ ] NVDEC/D3D11 硬件解码
- [ ] 多屏显示 (共享解码)

### Phase 3: 精确抓拍 (2 周)
- [ ] 压缩帧环形缓冲 (解码前截取)
- [ ] Pad Probe 精确帧 ID 捕获
- [ ] MP4 封装器 (无重编码)
- [ ] 抓拍触发机制

### Phase 4: 慢放 + 查看器 (1 周)
- [ ] 慢放播放器 (变速/步进/循环)
- [ ] 按需解码查看器 (5帧窗口)
- [ ] 导出功能 (GIF/MP4)

### Phase 5: UI + 测试 (1 周)
- [ ] QML UI 完整实现
- [ ] 配置同步 (WebSocket)
- [ ] 单元测试
- [ ] 性能调优

### 预计总工期: 8 周

---

## 九、风险与对策

| 风险 | 对策 |
|------|------|
| GStreamer Windows 兼容性 | 使用官方 MSVC 1.22+ 构建，避免 MinGW |
| Qt 许可证 | 使用 LGPL 版本，动态链接 |
| 硬件加速不可用 | 自动降级链：nvh264dec → d3d11h264dec → avdec_h264 |
| 学习曲线 | 核心逻辑可复用，GStreamer 管线设计已验证 |
| 内存溢出 | 压缩帧缓冲 + 按需解码，严格控制在 500MB 内 |

---

## 十、总结

### 核心优势

| 方面 | Java 版 (当前) | Qt C++ 版 (新) |
|------|---------------|----------------|
| **抓拍精度** | 400-500 帧延迟 | **0-2 帧** |
| **内存占用** | 500-800 MB | **400-500 MB** (满载) |
| **启动时间** | 3-5 秒 | **< 1 秒** |
| **马赛克问题** | 频繁 | **可控**（丢弃损坏帧） |
| **色彩调节** | 有延迟 | **实时 GPU** |

### 技术选型

**✅ GStreamer + Qt Quick**

- 复用现有 GStreamer 管线设计经验
- C++ Pad Probe 直接获取帧 ID，无 JNI 开销
- 压缩帧环形缓冲 (15MB) + MP4 存储（无重编码）
- 按需解码查看器（5 帧窗口 = 50MB）
- GPU 加速色彩调节（videobalance + gamma）
- Qt Quick 现代 UI，支持多屏/高 DPI

### 关键改进点

```
抓拍流程对比：

Java 版：
  解码帧 → JNI 回调 → Java → JPEG 编码 → 磁盘
  问题：JPEG 编码慢，帧差 400-500

C++ 版：
  压缩帧 → Pad Probe 直接截取 → 环形缓冲 → 封装 MP4
  优势：无解码/重编码，帧 ID 精确，0-2 帧延迟
```

这个方案能彻底解决当前 Java 版的核心痛点：**抓拍不精确** 和 **内存/性能问题**。


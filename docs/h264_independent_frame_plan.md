# H.264 独立帧文件改造方案

## 背景

当前截图和慢放链路经历过两类方案：

1. JPEG 独立帧文件方案：每帧天然独立，截图和慢放不依赖 H.264 参考链，因此不会出现 P 帧缺参考导致的解码问题；缺点是 JPEG 有损，和实时流相比还原度不够。
2. NALU / NaluFrameStore 方案：保存 H.264 数据，还原度高；但普通 H.264 流包含 P 帧，单个 `.nalu` 文件不一定能独立解码，随机访问时需要从关键帧重放参考链。当前还引入了环形缓存、关键帧 offset、链式解码等复杂逻辑。

目标是结合两者优点：

```text
JPEG 的独立性
+
H.264/.nalu 的高还原度
+
GStreamer 原生 pipeline
```

即：实时显示链路中的每一帧，同步生成一个独立 H.264 帧文件。截图和慢放都只按 `frameIndex` 读取这些独立帧文件，不再依赖 `NaluFrameStore` 环形缓存，也不再做 H.264 参考链重放。

## 核心目标

1. 每个保存文件对应一个真实显示帧。
2. 每个文件必须可独立解码。
3. 文件内容为高质量 H.264，而不是 JPEG。
4. 截图和慢放共用同一套独立帧文件。
5. 读取目标帧失败时不能返回上一帧冒充当前帧。
6. 不再依赖 `NaluFrameStore` 做截图和慢放的数据源。

## 非目标

1. 不继续优化 JPEG 还原度作为主方案。
2. 不再用普通 P 帧 NALU 作为随机访问文件。
3. 不实现手写 H.264 编码器。
4. 不让截图和慢放维护两套帧保存逻辑。

## 关键概念

### 普通 `.nalu` 文件不等于独立帧

普通 H.264 流中可能是：

```text
IDR, P, P, P, P, IDR, P, P...
```

如果每个 access unit 单独保存成文件，P 帧文件仍然依赖前面的 IDR 和参考帧。这样的文件虽然是一帧一个文件，但不能单独解码。

### 真正需要的文件格式

每个独立帧文件应包含：

```text
SPS
PPS
IDR frame
```

即每个文件都是一个可独立解码的 H.264 access unit / elementary stream chunk。

建议文件后缀使用 `.h264`，而不是 `.nalu`。原因是单个文件可能包含多个 NAL unit：SPS、PPS、SEI、IDR slice。若业务上仍希望叫 `.nalu`，也可以，但实现上应按独立 H.264 AU 文件理解。

## 推荐 GStreamer 管线

从解码后的 raw frame 分流，而不是从另一条 H.264 重解码链路绕行。

```text
WebRTC H.264
   │
   ▼
rtph264depay
   │
   ▼
h264parse
   │
   ▼
decoder
   │
   ▼
raw video frame
   │
   ▼
tee
   │
   ├───────────────────────────────┐
   │                               │
   ▼                               ▼
实时显示支路                    独立帧保存支路
queue                          queue
videoconvert                   videoconvert
appsink/videoSink              H.264 encoder all-I
                               key-int-max=1 / gop-size=1
                                  │
                                  ▼
                               h264parse config-interval=-1
                                  │
                                  ▼
                               video/x-h264
                               stream-format=byte-stream
                               alignment=au
                                  │
                                  ▼
                               multifilesink / appsink writer
                                  │
                                  ▼
                frames/session_000000100.h264
                frames/session_000000101.h264
                frames/session_000000102.h264
```

### 关键 GStreamer 参数

```text
key-int-max=1 或 gop-size=1
```

保证每帧都是关键帧 / intra frame。

```text
h264parse config-interval=-1
```

保证 SPS/PPS 跟随每个 IDR 输出，使单个文件可独立解码。

```text
video/x-h264,stream-format=byte-stream,alignment=au
```

保证输出是 Annex-B byte-stream，并且一个 buffer 对应一个 access unit。

```text
multifilesink next-file=buffer
```

让每个 access unit 写成一个文件。

## 编码器选择

优先级建议：

1. Windows 硬件优先：`mfh264enc gop-size=1`。
2. 可用时考虑硬件编码器：`nvh264enc` / `qsvh264enc`，具体取决于目标机器插件可用性。
3. 兜底软件编码：`x264enc key-int-max=1 tune=zerolatency`。

质量参数建议从高质量开始：

```text
mfh264enc:
  gop-size=1
  low-latency=true
  qp-i=12~18
  bitrate=30000 或更高
  max-bitrate=120000

x264enc:
  key-int-max=1
  tune=zerolatency
  speed-preset=veryfast 或 faster
  quantizer=12~18 或高 bitrate
```

最终参数需要用真实画面测试清晰度、文件体积、CPU/GPU 占用和延迟。

## 文件命名与索引

### 必须绑定真实 frameIndex

文件名必须使用业务真实帧号，而不是 `multifilesink` 自增保存序号。

推荐：

```text
captures/frames/{sessionPrefix}_{frameIndex:09d}.h264
```

例如：

```text
captures/frames/s_1737012345_000000100.h264
captures/frames/s_1737012345_000000101.h264
captures/frames/s_1737012345_000000102.h264
```

### multifilesink 的风险

`multifilesink location="...%09d.h264"` 默认编号是写入序号，不一定等于业务 `frameIndex`。如果保存支路丢帧、重连、reset，写入序号和业务帧号可能错位。

因此有两种实现路径：

#### 路径 A：继续用 multifilesink，但自定义 location

使用 `format-location` / `format-location-full` 信号，结合 buffer 上携带的 frameIndex，生成真实文件名。

优点：文件写入仍由 GStreamer 做。

风险：需要验证当前 GStreamer 版本和 C API 绑定是否方便拿到业务 frameIndex。

#### 路径 B：使用 appsink writer

管线输出到 appsink：

```text
encoder → h264parse → capsfilter(alignment=au) → appsink
```

在 appsink callback 中只做很薄的一层：

1. 取出 encoded H.264 AU。
2. 取出对应 frameIndex。
3. 写临时文件。
4. 原子 rename 成最终 `.h264` 文件。
5. 发出 `frameEncoded(frameIndex)` 信号。

优点：文件名和 frameIndex 绝对可控。

缺点：文件写入由我们做，但不需要自己实现 H.264 编码/解析。

推荐优先采用路径 B，因为当前业务强依赖 `frameIndex` 精准匹配，appsink writer 更容易保证不重复、不错位。

## 不重复的硬性约束

要彻底解决 `100-105` 与 `106-110` 整段重复，必须遵守以下约束：

1. 保存支路必须从实时显示同源 raw frame 分流。
2. 每个输出文件名必须绑定真实 `frameIndex`。
3. 目标帧文件不存在时，不能返回上一帧。
4. 保存支路不能 silent drop；如果压力过大，必须记录 missing，而不是伪造连续帧。
5. 截图和慢放必须读取同一个 `FrameFileStore`。
6. 每个 `.h264` 文件必须带 SPS/PPS + IDR，保证独立解码。

## 新模块建议：FrameFileStore

建议新增或抽象一个统一模块：

```text
FrameFileStore
```

职责：

```text
sessionPrefix + frameIndex → 独立帧文件
```

接口建议：

```cpp
class FrameFileStore : public QObject {
    Q_OBJECT
public:
    QString framePath(qint64 frameIndex) const;
    bool hasFrame(qint64 frameIndex) const;
    QByteArray readFrame(qint64 frameIndex) const;
    QImage decodeFrameToImage(qint64 frameIndex);

    int registerValidRange(qint64 start, qint64 end);
    void updateValidRange(int id, qint64 start, qint64 end);
    void unregisterValidRange(int id);

signals:
    void frameStored(qint64 frameIndex);
    void frameMissing(qint64 frameIndex);
};
```

它可以先接 H.264 独立帧后端；如果要保留 JPEG/PNG fallback，也可以作为后端之一，但截图/慢放上层不直接关心格式。

## CaptureManager 改造

当前 master 中 `CaptureManager` 依赖：

```text
NaluFrameStore
CaptureItem::naluDir
CaptureItem::keyFrameOffsets
decodeFromDisk()
checkPendingCaptures()
```

改造后：

```text
CaptureItem 只记录：
  startIndex
  eventIndex
  endIndex
  sessionPrefix
  validRangeId
```

截图触发时：

```text
1. 根据当前显示帧确定 eventIndex。
2. 计算 startIndex/endIndex。
3. 向 FrameFileStore 注册有效范围，防止这些帧文件被清理。
4. 不复制 NALU，不创建 item 独立 naluDir。
5. getFrameImage(item, offset) 直接读取 startIndex + offset 对应的独立 H.264 文件并解码。
```

`getFrameImage()` 规则：

```text
目标帧文件存在 → 解码并返回。
目标帧文件不存在 → 返回空图或等待态。
绝不返回上一帧冒充当前帧。
```

## SlowMotionPlayer 改造

当前 master 中慢放依赖：

```text
NaluFrameStore
SlowMotionDecodeThread
getDecodeSequence()
关键帧链式解码
```

改造后：

```text
1. 录制开始时记录 startIndex。
2. 持续监听 FrameFileStore::frameStored(frameIndex)。
3. 更新 endIndex/recordedFrames。
4. 播放时按 currentFrame → globalFrameIndex 读取独立 H.264 文件。
5. 每帧单独解码到 QVideoFrame。
```

不再需要：

```text
getDecodeSequence()
P 帧参考链
m_lastDecodedGlobal 顺序快路径
```

## 解码方式

虽然文件是 H.264，但每个文件都是独立 IDR，因此解码可以很简单：

```text
读取单个 .h264 文件
→ GstCaptureDecoder / 单帧解码器
→ QImage / QVideoFrame
```

不需要从关键帧重放到目标帧。

注意：解码器要能处理每个文件内的 SPS/PPS + IDR。如果 `h264parse config-interval=-1` 生效，单帧文件应满足这个条件。

## 清理策略

独立帧文件会持续增长，必须保留清理机制。

建议：

1. 默认只保留最近 N 帧或最近 N 秒。
2. 被截图 item 引用的范围注册为 valid range，不清理。
3. 慢放录制范围注册为 valid range，不清理。
4. 删除截图 item 或停止慢放后 unregister。
5. 清理只删除不在任何 valid range 内的旧文件。

## 原子写入

如果采用 appsink writer，写文件必须原子化：

```text
1. 写入 frames/session_000000100.h264.tmp
2. flush/close
3. rename 到 frames/session_000000100.h264
4. emit frameStored(100)
```

读取侧只读取最终文件，不读 `.tmp`。

## 测试计划

### 1. 独立解码测试

任意挑选帧文件：

```text
frames/session_000000100.h264
frames/session_000000101.h264
frames/session_000000102.h264
```

单独解码每个文件，确认不依赖前后文件。

### 2. 重复画面测试

在已知出现问题的场景里检查：

```text
100-105
106-110
```

确认不再出现整段重复。

### 3. 缺帧测试

模拟删除某个文件：

```text
frames/session_000000105.h264
```

UI 应显示 missing/空，不允许显示 104 或 106 冒充。

### 4. 慢放测试

验证：

1. 慢放倍数改变后播放速度明显变化。
2. 拖动到任意帧能显示正确画面。
3. 反复暂停/继续/跳帧不出现上一帧 fallback。

### 5. 截图测试

验证：

1. 截图 item 的 event frame 与实时看到的画面一致。
2. 前后帧滚动不重复、不跳错。
3. 删除 item 后对应 valid range 正确释放。

### 6. 压力测试

记录：

1. CPU/GPU 占用。
2. 磁盘写入 MB/s。
3. 单帧文件平均大小。
4. 保存队列是否积压。
5. 是否出现 missing frame。

## 实施顺序

### 阶段 1：建立独立 H.264 帧保存管线

1. 在 `GstPlayer` raw frame tee 后新增 all-IDR H.264 保存支路。
2. 使用 `mfh264enc gop-size=1` 或 `x264enc key-int-max=1`。
3. 接 `h264parse config-interval=-1`。
4. 输出 `stream-format=byte-stream,alignment=au`。
5. 用 appsink writer 或可控的 multifilesink 写入 `{sessionPrefix}_{frameIndex}.h264`。
6. 发出 `frameStored(frameIndex)` 信号。

### 阶段 2：截图切换到独立帧文件

1. `CaptureManager::capture()` 只记录 frame range。
2. 删除截图保存 `.nalu` 的逻辑。
3. `getFrameImage()` 读取独立 `.h264` 文件并单帧解码。
4. 禁止 fallback 到上一帧。

### 阶段 3：慢放切换到独立帧文件

1. `SlowMotionPlayer` 监听 `frameStored(frameIndex)`。
2. `SlowMotionDecodeThread` 改成单帧文件解码。
3. 删除 `getDecodeSequence()` 依赖。
4. 验证慢放倍数和拖动。

### 阶段 4：移除或降级 NaluFrameStore

1. 确认截图/慢放不再依赖 `NaluFrameStore`。
2. 保留旧逻辑作为临时 fallback 或彻底移除。
3. 清理 `decodeFromDisk()` 链式解码和 keyFrameOffsets。

## 风险与对策

| 风险 | 对策 |
|---|---|
| 编码器不可用 | mfh264enc 失败后 fallback 到 x264enc |
| 文件编号错位 | 文件名必须使用真实 frameIndex，不使用纯自增序号 |
| 保存支路积压 | 监控队列，必要时记录 missing，不伪造帧 |
| 单帧文件不能解码 | 确认 `config-interval=-1` 和 SPS/PPS 注入 |
| 文件体积过大 | 调整 QP/bitrate，必要时只保留最近窗口和 valid ranges |
| UI 仍显示重复 | 移除所有 lastValidImage fallback |

## 最终结论

可以不用 JPEG，也不用继续维护复杂 H.264 参考链。

推荐最终方案是：

```text
GStreamer raw frame tee
→ all-IDR H.264 encoder
→ h264parse config-interval=-1
→ alignment=au byte-stream
→ 每个真实 frameIndex 一个独立 .h264 文件
→ 截图和慢放统一读取这些文件
```

这个方案能同时满足：

```text
单帧独立
H.264 高还原度
不重复
不依赖 NaluFrameStore
截图和慢放统一数据源
```

//
//  gstsrtsource.h
//  Aifs（PC 拉流端）
//
//  ★ 方案 B：PC 端独立 SRT 拉流前段（与 WebRTC/P2P 链路解耦）
//
//  解耦红线：
//  - SRT 专属元素（srtsrc / tsdemux）的创建、配置、动态链接、销毁，全部封装在本类，
//    不写进 gstplayer.cpp 的 createPipeline()。
//  - GstPlayer 只负责「共享尾段」（h264parse → naluTee → 解码 → 显示 + 截图/慢放），
//    本类只负责把 SRT 解出的 H.264 接到 GstPlayer 提供的 h264parse 上。
//  - 删除本文件（.h/.cpp）+ GstPlayer 里 `// MARK: SRT` 分区即可完全回退。
//
//  管线：srtsrc → tsdemux → (video pad, 动态) → h264parse(GstPlayer 提供) → 共享尾段
//
//  依赖：GStreamer gst-plugins-bad 的 srtsrc + tsdemux（运行时插件，无需额外链接库）。
//

#ifndef GSTSRTSOURCE_H
#define GSTSRTSOURCE_H

#include <gst/gst.h>
#include <QString>

/// 独立 SRT 前段：srtsrc → tsdemux，动态把视频 PES 接到外部 h264parse。
///
/// 用法（在 GstPlayer 的 SRT 分区里）：
///   GstSrtSource src;
///   src.build(GST_BIN(pipeline), h264parse, "srt://ip:10080?streamid=...");
///   // 之后照常 link h264parse → naluTee → 解码 → 显示
///   ...
///   src.teardown(GST_BIN(pipeline));   // 销毁前调用
class GstSrtSource
{
public:
    GstSrtSource() = default;
    ~GstSrtSource();

    /// 校验 srtsrc / tsdemux 插件是否可用（gst-plugins-bad）。
    static bool pluginsAvailable(QString *missing = nullptr);

    /// 构建 SRT 前段并加入 pipeline，把 tsdemux 的视频 pad 动态链到 `h264parse` 的 sink。
    /// - pipeline: 目标 GstBin（即 GstPlayer 的 m_pipeline）。
    /// - h264parse: GstPlayer 已创建的共享 h264parse 元素（本类不拥有它）。
    /// - uri: 完整 SRT 地址，如 srt://47.122.115.33:10080?streamid=#!::r=live/<key>,m=request
    /// 返回 true 表示元素已创建并加入、且已挂好 pad-added 回调（实际 link 在收到视频 pad 时发生）。
    bool build(GstBin *pipeline, GstElement *h264parse, const QString &uri);

    /// 从 pipeline 移除并释放 SRT 前段元素（在 GstPlayer destroyPipeline 时调用）。
    /// 注意：若元素已被 pipeline 持有并随 pipeline 置 NULL 释放，这里只负责置空指针。
    void teardown(GstBin *pipeline);

    bool isBuilt() const { return m_srtsrc != nullptr; }

private:
    // tsdemux 动态 pad 回调：发现视频流时链到外部 h264parse。
    static void onTsDemuxPadAdded(GstElement *demux, GstPad *pad, gpointer userData);

    GstElement *m_srtsrc = nullptr;     // 本类创建（加入 pipeline 后由 pipeline 管理生命周期）
    GstElement *m_netQueue = nullptr;   // srtsrc → tsdemux 之间的突发平滑缓冲（治滑动碎花）
    GstElement *m_tsdemux = nullptr;    // 同上
    GstElement *m_h264parse = nullptr;  // 外部提供，不拥有
    bool m_videoLinked = false;
};

#endif // GSTSRTSOURCE_H

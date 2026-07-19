#ifndef WEBFRAMESOURCE_H
#define WEBFRAMESOURCE_H

#include <QObject>
#include <QByteArray>
#include <QImage>
#include <QString>
#include <QStringList>
#include <QHash>
#include <QSet>
#include <QPair>
#include <QMutex>
#include <QThreadPool>
#include <atomic>
#include "iframesource.h"

// ⭐ 网页内核帧源（2026-06-24，第三步 P5'）
//
//   网页内核(Chromium WebEngine)作主播放器时 GStreamer 完全让出，逐帧 H.264 落盘帧源为空。
//   WebFrameSource 提供与 GstPlayer 等价的「逐帧落盘 + 索引范围 + validRange 保护」能力，
//   只是帧来自 JS：webplayer_test.html 用 WebCodecs/MediaStreamTrackProcessor 维护帧环，
//   截图/慢放需要时把帧编成 JPEG（base64）经 kernelBridge 回传，WebFrameSource 落盘 + emit。
//
//   截图/慢放 UI、CaptureManager/SlowMotionPlayer 逻辑完全复用：它们只认 IFrameSource。
//   frameFormat()=JPEG，解码侧据此走 QImage::loadFromData（绕开 GstCaptureDecoder 软解）。
//
//   落盘目录与 GstPlayer 区分（captures/webframes），文件名 <prefix>_<index>.jpg。
class WebFrameSource : public QObject, public IFrameSource
{
    Q_OBJECT
public:
    explicit WebFrameSource(QObject *parent = nullptr);
    ~WebFrameSource() override;

    // ── IFrameSource 实现 ─────────────────────────────────────
    QObject *asQObject() override { return this; }
    IFrameSource::FrameFormat frameFormat() const override { return IFrameSource::FrameFormat::JPEG; }

    qint64 newestH264Frame() const override { return m_newest.load(std::memory_order_acquire); }
    qint64 oldestH264Frame() const override { return m_oldest.load(std::memory_order_acquire); }
    // 网页内核无「显示侧缓冲滞后」概念，事件帧即最新帧（offset=0）。
    int bufferSize() const override { return 0; }

    QByteArray readH264Frame(qint64 frameIndex) const override;   // 返回 JPEG 字节
    int registerH264ValidRange(qint64 start, qint64 end) override;
    void updateH264ValidRange(int id, qint64 start, qint64 end) override;
    void unregisterH264ValidRange(int id) override;

    QImage grabCurrentFrame() override;
    void requestKeyFrame() override {}   // 网页内核无 PLI 概念，空实现

    QString h264FrameDirectory() const override { return m_frameDir; }

    // ── 网页内核专用：JS 回传帧入口 ───────────────────────────
    // JS 把帧 JPEG（base64）经 kernelBridge 回传到这里：落盘 + 维护索引 + emit h264FrameStored。
    //   frameIndex<0 时由本类自增分配（连续帧环场景）。返回实际写入的全局帧索引。
    Q_INVOKABLE qint64 pushJpegFrame(const QByteArray &jpeg, qint64 frameIndex = -1);

    // 复位（切流/停播）：清空索引、validRange、删除落盘文件。
    Q_INVOKABLE void reset();

signals:
    // ⭐ 与 GstPlayer 同名信号，CaptureManager/SlowMotionPlayer 用 SIGNAL 宏统一连接。
    void h264FrameStored(qint64 frameIndex);

private:
    QString framePath(qint64 frameIndex) const;
    QStringList cleanupFramesLocked();   // §23.17：锁内只摘索引，返回待删文件路径（删除在锁外/后台做）
    void removeFilesAsync(const QStringList &paths);
    bool isProtectedLocked(qint64 frameIndex) const;
    void recomputeOldestLocked();

    QString m_frameDir;
    QString m_sessionPrefix;

    mutable QMutex m_mutex;
    std::atomic<qint64> m_oldest{-1};
    std::atomic<qint64> m_newest{-1};
    std::atomic<qint64> m_nextIndex{0};
    QSet<qint64> m_available;                       // 已落盘可用帧
    QHash<int, QPair<qint64, qint64>> m_validRanges; // 保护范围
    int m_nextValidRangeId = 1;

    QImage m_lastFrame;                             // 最近一帧（grabCurrentFrame 兜底）

    // §23.17：写盘/删文件专用单线程池（保序），主线程只做索引分配后立即返回。
    QThreadPool m_ioPool;
    // §23.17：会话代际。reset() 递增；写盘任务完成时代际已变 = 帧属于上一场，丢弃不入索引。
    std::atomic<int> m_generation{0};

    // 与 GstPlayer 同口径的滚动清理参数（受 validRange 保护的帧永不清理，
    //   所以即便慢放录制 5000 帧也不会丢；keep-count 只回收无保护的 live 帧）。
    static constexpr qint64 FRAME_KEEP_COUNT = 3000; // 与 GstPlayer 一致
    static constexpr qint64 SAFETY_MARGIN = 300;     // 最新若干帧不清理
};

#endif // WEBFRAMESOURCE_H

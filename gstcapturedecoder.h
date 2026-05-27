#ifndef GSTCAPTUREDECODER_H
#define GSTCAPTUREDECODER_H

#include <QByteArray>
#include <QImage>
#include <QMutex>

typedef struct _GstElement GstElement;

class GstCaptureDecoder
{
public:
    GstCaptureDecoder();
    ~GstCaptureDecoder();

    // 推送 NALU 但不等待解码结果（用于中间帧快进）
    bool pushNalu(const QByteArray &naluData);

    // 推送 NALU 并拉取解码结果（用于目标帧）
    QImage decodeNalu(const QByteArray &naluData);

    // 拉取最新解码帧（pushNalu 之后调用）
    QImage pullLatest();

    void flush();

private:
    bool ensurePipeline();
    void cleanup();
    void drainAppsink();

    GstElement *m_pipeline = nullptr;
    GstElement *m_appsrc = nullptr;
    GstElement *m_appsink = nullptr;
    bool m_ready = false;
    quint64 m_pts = 0;
    QMutex m_mutex;
};

#endif // GSTCAPTUREDECODER_H

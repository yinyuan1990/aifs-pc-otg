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

    QImage decodeNalu(const QByteArray &naluData);
    void flush();

private:
    bool ensurePipeline();
    void cleanup();
    QImage pullFrame();

    GstElement *m_pipeline = nullptr;
    GstElement *m_appsrc = nullptr;
    GstElement *m_appsink = nullptr;
    bool m_ready = false;
    quint64 m_pts = 0;
    QMutex m_mutex;
};

#endif // GSTCAPTUREDECODER_H

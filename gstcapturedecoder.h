#ifndef GSTCAPTUREDECODER_H
#define GSTCAPTUREDECODER_H

#include <QByteArray>
#include <QImage>
#include <QMutex>

struct AVCodecContext;
struct AVFrame;
struct AVPacket;
struct SwsContext;

class GstCaptureDecoder
{
public:
    GstCaptureDecoder();
    ~GstCaptureDecoder();

    bool pushNalu(const QByteArray &naluData);
    QImage decodeNalu(const QByteArray &naluData);
    QImage pullLatest();
    void flush();

private:
    bool ensureDecoder();
    void cleanup();
    QImage frameToImage();

    AVCodecContext *m_codecCtx = nullptr;
    AVFrame *m_frame = nullptr;
    AVPacket *m_packet = nullptr;
    SwsContext *m_swsCtx = nullptr;
    int m_swsWidth = 0;
    int m_swsHeight = 0;
    QImage m_lastDecoded;
    QMutex m_mutex;
};

#endif // GSTCAPTUREDECODER_H

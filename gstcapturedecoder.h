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

    // gz.txt 用：最近一次 decodeNalu 里「H264 解码」和「转成 RGB」各用了多少毫秒
    qint64 lastDecodeMs() const { return m_lastDecodeMs; }
    qint64 lastConvertMs() const { return m_lastConvertMs; }

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
    int m_swsFmt = -1;
    QImage m_lastDecoded;
    qint64 m_lastDecodeMs = 0;
    qint64 m_lastConvertMs = 0;
    QMutex m_mutex;
};

#endif // GSTCAPTUREDECODER_H

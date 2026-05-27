#include "gstcapturedecoder.h"
#include "capturedebuglog.h"
#include <QDebug>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

GstCaptureDecoder::GstCaptureDecoder()
{
}

GstCaptureDecoder::~GstCaptureDecoder()
{
    cleanup();
}

bool GstCaptureDecoder::ensureDecoder()
{
    if (m_codecCtx) return true;

    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        captureDebugLog("DEC", "avcodec_find_decoder H264 FAIL");
        return false;
    }

    m_codecCtx = avcodec_alloc_context3(codec);
    if (!m_codecCtx) return false;

    m_codecCtx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    m_codecCtx->thread_count = 1;

    if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
        captureDebugLog("DEC", "avcodec_open2 H264 FAIL");
        avcodec_free_context(&m_codecCtx);
        return false;
    }

    m_frame = av_frame_alloc();
    m_packet = av_packet_alloc();

    captureDebugLog("DEC", "ensureDecoder OK (avcodec LOW_DELAY)");
    return true;
}

void GstCaptureDecoder::cleanup()
{
    if (m_swsCtx) { sws_freeContext(m_swsCtx); m_swsCtx = nullptr; }
    if (m_frame) { av_frame_free(&m_frame); m_frame = nullptr; }
    if (m_packet) { av_packet_free(&m_packet); m_packet = nullptr; }
    if (m_codecCtx) { avcodec_free_context(&m_codecCtx); m_codecCtx = nullptr; }
    m_swsWidth = 0;
    m_swsHeight = 0;
    m_lastDecoded = QImage();
}

QImage GstCaptureDecoder::frameToImage()
{
    if (!m_frame || m_frame->width <= 0 || m_frame->height <= 0)
        return QImage();

    int w = m_frame->width;
    int h = m_frame->height;

    if (!m_swsCtx || m_swsWidth != w || m_swsHeight != h) {
        if (m_swsCtx) sws_freeContext(m_swsCtx);
        m_swsCtx = sws_getContext(w, h, (AVPixelFormat)m_frame->format,
                                  w, h, AV_PIX_FMT_BGRA,
                                  SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        m_swsWidth = w;
        m_swsHeight = h;
    }
    if (!m_swsCtx) return QImage();

    QImage result(w, h, QImage::Format_ARGB32);
    uint8_t *dst[1] = { result.bits() };
    int dstStride[1] = { static_cast<int>(result.bytesPerLine()) };
    sws_scale(m_swsCtx, m_frame->data, m_frame->linesize, 0, h, dst, dstStride);

    return result;
}

bool GstCaptureDecoder::pushNalu(const QByteArray &naluData)
{
    QMutexLocker lock(&m_mutex);
    if (naluData.isEmpty() || !ensureDecoder()) return false;

    m_packet->data = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(naluData.constData()));
    m_packet->size = naluData.size();

    int ret = avcodec_send_packet(m_codecCtx, m_packet);
    m_packet->data = nullptr;
    m_packet->size = 0;
    if (ret < 0) return false;

    while (avcodec_receive_frame(m_codecCtx, m_frame) == 0) {
        m_lastDecoded = frameToImage();
    }
    return true;
}

QImage GstCaptureDecoder::pullLatest()
{
    QMutexLocker lock(&m_mutex);
    return m_lastDecoded;
}

QImage GstCaptureDecoder::decodeNalu(const QByteArray &naluData)
{
    QMutexLocker lock(&m_mutex);
    if (naluData.isEmpty() || !ensureDecoder()) return QImage();

    CaptureDebugScope scope("DEC", "decodeNalu", 100);

    m_packet->data = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(naluData.constData()));
    m_packet->size = naluData.size();

    int ret = avcodec_send_packet(m_codecCtx, m_packet);
    m_packet->data = nullptr;
    m_packet->size = 0;

    if (ret < 0) {
        captureDebugLog("DEC", QString("send_packet FAIL ret=%1 %2")
            .arg(ret).arg(captureDebugNaluPreview(naluData)));
        return QImage();
    }

    ret = avcodec_receive_frame(m_codecCtx, m_frame);
    if (ret < 0) {
        captureDebugLog("DEC", QString("receive_frame FAIL ret=%1 %2")
            .arg(ret).arg(captureDebugNaluPreview(naluData)));
        return QImage();
    }

    QImage result = frameToImage();
    if (!result.isNull()) {
        m_lastDecoded = result;
        captureDebugLog("DEC", QString("decodeNalu OK %1x%2 %3")
            .arg(m_frame->width).arg(m_frame->height)
            .arg(captureDebugNaluPreview(naluData)));
    }
    return result;
}

void GstCaptureDecoder::flush()
{
    QMutexLocker lock(&m_mutex);
    if (m_codecCtx) {
        avcodec_flush_buffers(m_codecCtx);
    }
    m_lastDecoded = QImage();
}

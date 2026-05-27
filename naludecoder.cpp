#include "naludecoder.h"
#include "naluframestore.h"
#include <QDebug>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/hwcontext.h>
#include <libswscale/swscale.h>
}

NaluDecoder::NaluDecoder(NaluFrameStore *store, QObject *parent)
    : QObject(parent)
    , m_store(store)
{
}

NaluDecoder::~NaluDecoder()
{
    cleanupDecoder();
}

static enum AVPixelFormat naluDecoderHwGetFormat(AVCodecContext *ctx, const enum AVPixelFormat *pix_fmts)
{
    NaluDecoder *self = static_cast<NaluDecoder*>(ctx->opaque);
    enum AVPixelFormat target = static_cast<AVPixelFormat>(self->hwPixFmt());
    for (const enum AVPixelFormat *p = pix_fmts; *p != AV_PIX_FMT_NONE; p++) {
        if (*p == target) return *p;
    }
    return pix_fmts[0];
}

bool NaluDecoder::ensureDecoder()
{
    if (m_codecCtx) return true;

    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        qWarning() << "NaluDecoder: H.264 decoder not found";
        return false;
    }

    m_codecCtx = avcodec_alloc_context3(codec);
    if (!m_codecCtx) return false;

    // Try GPU hardware decode
    m_useHwDecode = false;
    m_hwPixFmt = -1;

    AVHWDeviceType hwTypes[] = {
        AV_HWDEVICE_TYPE_D3D11VA,
        AV_HWDEVICE_TYPE_DXVA2,
        AV_HWDEVICE_TYPE_NONE
    };

    for (int i = 0; hwTypes[i] != AV_HWDEVICE_TYPE_NONE; i++) {
        if (av_hwdevice_ctx_create(&m_hwDeviceCtx, hwTypes[i], nullptr, nullptr, 0) >= 0) {
            for (int j = 0; ; j++) {
                const AVCodecHWConfig *config = avcodec_get_hw_config(codec, j);
                if (!config) break;
                if (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX &&
                    config->device_type == hwTypes[i]) {
                    m_hwPixFmt = config->pix_fmt;
                    break;
                }
            }

            if (m_hwPixFmt != -1) {
                m_codecCtx->hw_device_ctx = av_buffer_ref(m_hwDeviceCtx);
                m_codecCtx->opaque = this;
                m_codecCtx->get_format = naluDecoderHwGetFormat;
                m_useHwDecode = true;
                qDebug() << "NaluDecoder: GPU hardware decode:" << av_hwdevice_get_type_name(hwTypes[i]);
                break;
            } else {
                av_buffer_unref(&m_hwDeviceCtx);
                m_hwDeviceCtx = nullptr;
            }
        }
    }

    m_codecCtx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    m_codecCtx->flags2 |= AV_CODEC_FLAG2_FAST;
    m_codecCtx->thread_count = m_useHwDecode ? 1 : 2;

    if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
        // Hardware decode failed — fallback to software
        if (m_hwDeviceCtx) { av_buffer_unref(&m_hwDeviceCtx); m_hwDeviceCtx = nullptr; }
        avcodec_free_context(&m_codecCtx);
        m_useHwDecode = false;
        m_hwPixFmt = -1;

        codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        m_codecCtx = avcodec_alloc_context3(codec);
        if (!m_codecCtx) return false;
        m_codecCtx->flags |= AV_CODEC_FLAG_LOW_DELAY;
        m_codecCtx->flags2 |= AV_CODEC_FLAG2_FAST;
        m_codecCtx->thread_count = 2;

        if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
            avcodec_free_context(&m_codecCtx);
            qWarning() << "NaluDecoder: all decode methods failed";
            return false;
        }
        qDebug() << "NaluDecoder: GPU failed, using CPU software decode";
    }

    m_avFrame = av_frame_alloc();
    m_swFrame = av_frame_alloc();
    qDebug() << "NaluDecoder: decoder initialized, hw=" << m_useHwDecode;
    return true;
}

void NaluDecoder::cleanupDecoder()
{
    if (m_swsCtx) {
        sws_freeContext(m_swsCtx);
        m_swsCtx = nullptr;
    }
    if (m_swFrame) {
        av_frame_free(&m_swFrame);
    }
    if (m_avFrame) {
        av_frame_free(&m_avFrame);
    }
    if (m_codecCtx) {
        avcodec_free_context(&m_codecCtx);
    }
    if (m_hwDeviceCtx) {
        av_buffer_unref(&m_hwDeviceCtx);
        m_hwDeviceCtx = nullptr;
    }
    m_swsWidth = 0;
    m_swsHeight = 0;
    m_swsSrcFmt = -1;
    m_useHwDecode = false;
    m_hwPixFmt = -1;
}

void NaluDecoder::flush()
{
    QMutexLocker lock(&m_mutex);
    if (m_codecCtx) {
        avcodec_flush_buffers(m_codecCtx);
    }
    m_lastDecodedIndex = -1;
}

void NaluDecoder::clearCache()
{
    QMutexLocker lock(&m_mutex);
    m_cache.clear();
    m_accessCounter = 0;
}

void NaluDecoder::evictCache()
{
    while (m_cache.size() >= MAX_CACHE) {
        qint64 lruKey = -1;
        qint64 lruOrder = INT64_MAX;
        for (auto it = m_cache.begin(); it != m_cache.end(); ++it) {
            if (it.value().accessOrder < lruOrder) {
                lruOrder = it.value().accessOrder;
                lruKey = it.key();
            }
        }
        if (lruKey >= 0) {
            m_cache.remove(lruKey);
        } else {
            break;
        }
    }
}

QByteArray NaluDecoder::ensureAnnexB(const QByteArray &data)
{
    if (data.size() < 4) return data;
    const uint8_t *p = reinterpret_cast<const uint8_t*>(data.constData());
    int size = data.size();

    if ((p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 1) ||
        (p[0] == 0 && p[1] == 0 && p[2] == 1)) {
        return data;
    }

    static const char sc[4] = {0, 0, 0, 1};
    QByteArray result;
    result.reserve(size + 64);
    int pos = 0;
    bool parsed = false;

    while (pos + 4 <= size) {
        uint32_t nalLen = (uint32_t(p[pos]) << 24) | (uint32_t(p[pos+1]) << 16) |
                          (uint32_t(p[pos+2]) << 8) | uint32_t(p[pos+3]);
        if (nalLen == 0 || nalLen > uint32_t(size - pos - 4)) break;
        pos += 4;
        result.append(sc, 4);
        result.append(reinterpret_cast<const char*>(p + pos), int(nalLen));
        pos += int(nalLen);
        parsed = true;
    }

    if (parsed && pos >= size - 3) return result;

    result.clear();
    result.reserve(size + 4);
    result.append(sc, 4);
    result.append(data);
    return result;
}

QImage NaluDecoder::decodeOneNalu(const QByteArray &naluData)
{
    if (!m_codecCtx || naluData.isEmpty()) return QImage();

    QByteArray feed = ensureAnnexB(naluData);

    AVPacket *pkt = av_packet_alloc();
    pkt->data = reinterpret_cast<uint8_t*>(feed.data());
    pkt->size = feed.size();

    int ret = avcodec_send_packet(m_codecCtx, pkt);

    pkt->data = nullptr;
    pkt->size = 0;
    av_packet_free(&pkt);

    if (ret < 0) return QImage();

    ret = avcodec_receive_frame(m_codecCtx, m_avFrame);
    if (ret < 0) return QImage();

    // If hardware frame, transfer to CPU
    AVFrame *srcFrame = m_avFrame;
    if (m_useHwDecode && m_avFrame->format == m_hwPixFmt) {
        av_frame_unref(m_swFrame);
        if (av_hwframe_transfer_data(m_swFrame, m_avFrame, 0) < 0) {
            return QImage();
        }
        srcFrame = m_swFrame;
    }

    int w = srcFrame->width;
    int h = srcFrame->height;
    if (w <= 0 || h <= 0) return QImage();

    int srcFmt = srcFrame->format;
    if (m_swsWidth != w || m_swsHeight != h || m_swsSrcFmt != srcFmt || !m_swsCtx) {
        if (m_swsCtx) sws_freeContext(m_swsCtx);
        m_swsCtx = sws_getContext(w, h, static_cast<AVPixelFormat>(srcFmt),
                                   w, h, AV_PIX_FMT_BGRA,
                                   SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        m_swsWidth = w;
        m_swsHeight = h;
        m_swsSrcFmt = srcFmt;
    }

    if (!m_swsCtx) return QImage();

    QImage img(w, h, QImage::Format_ARGB32);
    uint8_t *dstData[1] = { img.bits() };
    int dstLinesize[1] = { static_cast<int>(img.bytesPerLine()) };

    sws_scale(m_swsCtx, srcFrame->data, srcFrame->linesize, 0, h,
              dstData, dstLinesize);

    return img;
}

QImage NaluDecoder::tryCachedFrame(qint64 frameIndex)
{
    if (!m_mutex.tryLock()) return QImage();
    auto it = m_cache.find(frameIndex);
    QImage result;
    if (it != m_cache.end()) {
        it.value().accessOrder = ++m_accessCounter;
        result = it.value().image;
    }
    m_mutex.unlock();
    return result;
}

bool NaluDecoder::canDecodeQuickly(qint64 frameIndex)
{
    if (!m_mutex.tryLock()) return false;
    bool quick = m_cache.contains(frameIndex) || (frameIndex == m_lastDecodedIndex + 1);
    m_mutex.unlock();
    return quick;
}

QImage NaluDecoder::decodeFrame(qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);

    auto cacheIt = m_cache.find(frameIndex);
    if (cacheIt != m_cache.end()) {
        cacheIt.value().accessOrder = ++m_accessCounter;
        return cacheIt.value().image;
    }

    if (!m_store || !m_store->hasFrame(frameIndex)) return QImage();
    if (!ensureDecoder()) return QImage();

    bool sequential = (frameIndex == m_lastDecodedIndex + 1) && !m_store->isKeyFrame(frameIndex);

    if (sequential) {
        QByteArray data = m_store->getFrame(frameIndex);
        QImage img = decodeOneNalu(data);
        if (!img.isNull()) {
            m_lastDecodedIndex = frameIndex;
            evictCache();
            m_cache[frameIndex] = {img, ++m_accessCounter};
            return img;
        }
    }

    auto sequence = m_store->getDecodeSequence(frameIndex);
    if (sequence.isEmpty()) return QImage();

    avcodec_flush_buffers(m_codecCtx);

    QImage result;
    for (const auto &pair : sequence) {
        QImage img = decodeOneNalu(pair.first);
        if (!img.isNull()) {
            if (!m_cache.contains(pair.second)) {
                evictCache();
                m_cache[pair.second] = {img, ++m_accessCounter};
            }
            if (pair.second == frameIndex) {
                result = img;
            }
        }
    }

    m_lastDecodedIndex = frameIndex;
    return result;
}

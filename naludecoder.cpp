#include "naludecoder.h"
#include "naluframestore.h"
#include <QDebug>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
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

    m_codecCtx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    m_codecCtx->flags2 |= AV_CODEC_FLAG2_FAST;
    m_codecCtx->thread_count = 2;

    if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
        avcodec_free_context(&m_codecCtx);
        qWarning() << "NaluDecoder: failed to open decoder";
        return false;
    }

    m_avFrame = av_frame_alloc();
    qDebug() << "NaluDecoder: H.264 decoder initialized";
    return true;
}

void NaluDecoder::cleanupDecoder()
{
    if (m_swsCtx) {
        sws_freeContext(m_swsCtx);
        m_swsCtx = nullptr;
    }
    if (m_avFrame) {
        av_frame_free(&m_avFrame);
    }
    if (m_codecCtx) {
        avcodec_free_context(&m_codecCtx);
    }
    m_swsWidth = 0;
    m_swsHeight = 0;
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

    int w = m_avFrame->width;
    int h = m_avFrame->height;
    if (w <= 0 || h <= 0) return QImage();

    if (m_swsWidth != w || m_swsHeight != h || !m_swsCtx) {
        if (m_swsCtx) sws_freeContext(m_swsCtx);
        m_swsCtx = sws_getContext(w, h, static_cast<AVPixelFormat>(m_avFrame->format),
                                   w, h, AV_PIX_FMT_BGRA,
                                   SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        m_swsWidth = w;
        m_swsHeight = h;
    }

    if (!m_swsCtx) return QImage();

    QImage img(w, h, QImage::Format_ARGB32);
    uint8_t *dstData[1] = { img.bits() };
    int dstLinesize[1] = { static_cast<int>(img.bytesPerLine()) };

    sws_scale(m_swsCtx, m_avFrame->data, m_avFrame->linesize, 0, h,
              dstData, dstLinesize);

    return img;
}

QImage NaluDecoder::tryCachedFrame(qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);
    auto it = m_cache.find(frameIndex);
    if (it != m_cache.end()) {
        it.value().accessOrder = ++m_accessCounter;
        return it.value().image;
    }
    return QImage();
}

bool NaluDecoder::canDecodeQuickly(qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);
    if (m_cache.contains(frameIndex)) return true;
    if (frameIndex == m_lastDecodedIndex + 1) return true;
    return false;
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

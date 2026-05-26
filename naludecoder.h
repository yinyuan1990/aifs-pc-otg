#ifndef NALUDECODER_H
#define NALUDECODER_H

#include <QObject>
#include <QImage>
#include <QHash>
#include <QMutex>

struct AVCodecContext;
struct AVFrame;
struct SwsContext;

class NaluFrameStore;

class NaluDecoder : public QObject
{
    Q_OBJECT
public:
    explicit NaluDecoder(NaluFrameStore *store, QObject *parent = nullptr);
    ~NaluDecoder();

    QImage decodeFrame(qint64 frameIndex);
    QImage tryCachedFrame(qint64 frameIndex);
    bool canDecodeQuickly(qint64 frameIndex);
    void clearCache();
    void flush();

private:
    bool ensureDecoder();
    void cleanupDecoder();
    QImage decodeOneNalu(const QByteArray &naluData);
    void evictCache();
    static QByteArray ensureAnnexB(const QByteArray &data);

    NaluFrameStore *m_store;

    AVCodecContext *m_codecCtx = nullptr;
    AVFrame *m_avFrame = nullptr;
    SwsContext *m_swsCtx = nullptr;
    int m_swsWidth = 0;
    int m_swsHeight = 0;

    struct CacheEntry {
        QImage image;
        qint64 accessOrder;
    };
    QHash<qint64, CacheEntry> m_cache;
    qint64 m_accessCounter = 0;
    static constexpr int MAX_CACHE = 10;

    qint64 m_lastDecodedIndex = -1;

    mutable QMutex m_mutex;
};

#endif // NALUDECODER_H

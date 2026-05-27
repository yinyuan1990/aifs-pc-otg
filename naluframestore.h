#ifndef NALUFRAMESTORE_H
#define NALUFRAMESTORE_H

#include <QObject>
#include <QByteArray>
#include <QVector>
#include <QHash>
#include <QList>
#include <QMap>
#include <QPair>
#include <QMutex>
#include <QFile>

class NaluFrameStore : public QObject
{
    Q_OBJECT
public:
    static constexpr int DEFAULT_CAPACITY = 3000;  // ~50s@60fps，降低 mmap 压力

    explicit NaluFrameStore(int capacity = DEFAULT_CAPACITY, QObject *parent = nullptr);
    ~NaluFrameStore();

    void addFrame(const QByteArray &naluData, qint64 frameIndex, bool isKeyFrame);

    QByteArray getFrame(qint64 frameIndex) const;
    bool hasFrame(qint64 frameIndex) const;
    bool isKeyFrame(qint64 frameIndex) const;

    qint64 findNearestKeyFrame(qint64 frameIndex) const;
    QVector<QPair<QByteArray, qint64>> getDecodeSequence(qint64 targetIndex) const;

    qint64 oldestIndex() const;
    qint64 newestIndex() const;
    int count() const;
    int capacity() const { return m_capacity; }

    int registerValidRange(qint64 startIndex, qint64 endIndex);
    void unregisterValidRange(int rangeId);
    void clearAllValidRanges();

    void clear();

signals:
    void frameStored(qint64 frameIndex);

private:
    struct FrameEntry {
        qint64 frameIndex = -1;
        quint64 dataOffset = 0;
        quint32 dataSize = 0;
        bool isKeyFrame = false;
    };

    bool isProtected(qint64 frameIndex) const;
    bool initMmap();
    void cleanupMmap();

    QVector<FrameEntry> m_index;
    int m_capacity;
    int m_head = 0;
    int m_count = 0;

    QHash<qint64, int> m_indexMap;
    QList<qint64> m_keyFrameList;

    QFile m_dataFile;
    uchar *m_dataPtr = nullptr;
    quint64 m_dataCapacity = 0;
    quint64 m_dataWritePos = 0;

    QMap<int, QPair<qint64, qint64>> m_validRanges;
    int m_nextRangeId = 0;

    mutable QMutex m_mutex;
};

#endif // NALUFRAMESTORE_H

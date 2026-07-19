#include "naluframestore.h"
#include <QDebug>
#include <QDir>
#include <QStandardPaths>
#include <QThreadPool>
#include <algorithm>
#include <cstring>

static constexpr quint64 BYTES_PER_FRAME = 50 * 1024; // 50KB average per NALU

NaluFrameStore::NaluFrameStore(int capacity, QObject *parent)
    : QObject(parent)
    , m_capacity(capacity)
{
    m_index.resize(capacity);

    if (initMmap()) {
        qDebug() << "NaluFrameStore: mmap OK, capacity" << capacity
                 << "data" << (m_dataCapacity / 1024 / 1024) << "MB";
    } else {
        qWarning() << "NaluFrameStore: mmap failed";
    }
}

NaluFrameStore::~NaluFrameStore()
{
    cleanupMmap();
}

bool NaluFrameStore::initMmap()
{
    QString tempDir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    QString path = QDir(tempDir).filePath(
        QString("nalustore_%1.dat").arg(reinterpret_cast<quintptr>(this), 0, 16));

    m_dataFile.setFileName(path);
    if (!m_dataFile.open(QIODevice::ReadWrite)) {
        qWarning() << "NaluFrameStore: cannot open" << path;
        return false;
    }

    m_dataCapacity = static_cast<quint64>(m_capacity) * BYTES_PER_FRAME;
    if (!m_dataFile.resize(static_cast<qint64>(m_dataCapacity))) {
        qWarning() << "NaluFrameStore: resize failed" << m_dataCapacity;
        m_dataFile.close();
        return false;
    }

    m_dataPtr = m_dataFile.map(0, static_cast<qint64>(m_dataCapacity));
    if (!m_dataPtr) {
        qWarning() << "NaluFrameStore: map failed";
        m_dataFile.close();
        return false;
    }

    m_dataWritePos = 0;
    return true;
}

void NaluFrameStore::cleanupMmap()
{
    if (m_dataPtr) {
        m_dataFile.unmap(m_dataPtr);
        m_dataPtr = nullptr;
    }
    if (m_dataFile.isOpen()) {
        QString path = m_dataFile.fileName();
        m_dataFile.close();
        // §23.16：150MB 级 mmap 数据文件的删除移到后台线程——析构多发生在主线程
        //（deleteChildren），freeze_diag 实锤 DeleteFileW 单次挂主线程 ~2s。
        QThreadPool::globalInstance()->start([path]() {
            QFile::remove(path);
        });
    }
}

void NaluFrameStore::addFrame(const QByteArray &naluData, qint64 frameIndex, bool isKeyFrame)
{
    {
        QMutexLocker lock(&m_mutex);

        quint32 dataSize = static_cast<quint32>(naluData.size());

        // Evict oldest slot if ring buffer full
        if (m_count >= m_capacity) {
            FrameEntry &old = m_index[m_head];
            if (old.frameIndex >= 0) {
                if (isProtected(old.frameIndex)) {
                    int attempts = 0;
                    int tryHead = m_head;
                    while (attempts < m_capacity) {
                        tryHead = (tryHead + 1) % m_capacity;
                        FrameEntry &tryEntry = m_index[tryHead];
                        if (tryEntry.frameIndex < 0 || !isProtected(tryEntry.frameIndex)) {
                            m_head = tryHead;
                            break;
                        }
                        attempts++;
                    }
                    if (attempts >= m_capacity) {
                        qWarning() << "NaluFrameStore: all slots protected, overwriting oldest";
                    }
                }

                FrameEntry &victim = m_index[m_head];
                if (victim.frameIndex >= 0) {
                    m_indexMap.remove(victim.frameIndex);
                    if (victim.isKeyFrame) {
                        m_keyFrameList.removeOne(victim.frameIndex);
                    }
                    victim.dataCopy.clear();
                }
            }
        }

        // Write NALU data to mmap region
        quint64 dataOffset = 0;
        if (m_dataPtr && dataSize > 0) {
            if (m_dataWritePos + dataSize > m_dataCapacity) {
                m_dataWritePos = 0;
            }
            quint64 writeStart = m_dataWritePos;
            quint64 writeEnd = writeStart + dataSize;
            for (int i = 0; i < m_capacity; i++) {
                FrameEntry &e = m_index[i];
                if (e.frameIndex >= 0 && e.dataSize > 0 && e.dataCopy.isEmpty()
                    && isProtected(e.frameIndex)) {
                    quint64 eStart = e.dataOffset;
                    quint64 eEnd = eStart + e.dataSize;
                    if (writeStart < eEnd && writeEnd > eStart) {
                        e.dataCopy = QByteArray(
                            reinterpret_cast<const char*>(m_dataPtr + e.dataOffset),
                            static_cast<int>(e.dataSize));
                    }
                }
            }
            dataOffset = m_dataWritePos;
            std::memcpy(m_dataPtr + m_dataWritePos, naluData.constData(), dataSize);
            m_dataWritePos += dataSize;
        }

        FrameEntry &entry = m_index[m_head];
        entry.frameIndex = frameIndex;
        entry.dataOffset = dataOffset;
        entry.dataSize = dataSize;
        entry.isKeyFrame = isKeyFrame;

        m_indexMap[frameIndex] = m_head;

        if (isKeyFrame) {
            auto it = std::lower_bound(m_keyFrameList.begin(), m_keyFrameList.end(), frameIndex);
            m_keyFrameList.insert(it, frameIndex);
        }

        m_head = (m_head + 1) % m_capacity;
        if (m_count < m_capacity) m_count++;
    }

    emit frameStored(frameIndex);
}

QByteArray NaluFrameStore::getFrame(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    auto it = m_indexMap.find(frameIndex);
    if (it == m_indexMap.end()) return QByteArray();

    const FrameEntry &entry = m_index[it.value()];
    if (!entry.dataCopy.isEmpty()) return entry.dataCopy;
    if (!m_dataPtr || entry.dataSize == 0) return QByteArray();

    return QByteArray(reinterpret_cast<const char*>(m_dataPtr + entry.dataOffset),
                      static_cast<int>(entry.dataSize));
}

bool NaluFrameStore::hasFrame(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    return m_indexMap.contains(frameIndex);
}

bool NaluFrameStore::isKeyFrame(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    auto it = m_indexMap.find(frameIndex);
    if (it == m_indexMap.end()) return false;
    return m_index[it.value()].isKeyFrame;
}

qint64 NaluFrameStore::findNearestKeyFrame(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    if (m_keyFrameList.isEmpty()) return -1;

    auto it = std::upper_bound(m_keyFrameList.begin(), m_keyFrameList.end(), frameIndex);
    if (it == m_keyFrameList.begin()) {
        return *it;
    }
    --it;
    return *it;
}

QVector<QPair<QByteArray, qint64>> NaluFrameStore::getDecodeSequence(qint64 targetIndex) const
{
    QMutexLocker lock(&m_mutex);

    QVector<QPair<QByteArray, qint64>> result;
    if (!m_indexMap.contains(targetIndex)) return result;

    qint64 keyFrameIdx = -1;
    {
        auto it = std::upper_bound(m_keyFrameList.begin(), m_keyFrameList.end(), targetIndex);
        if (it != m_keyFrameList.begin()) {
            --it;
            keyFrameIdx = *it;
        } else if (!m_keyFrameList.isEmpty()) {
            keyFrameIdx = m_keyFrameList.first();
        }
    }

    if (keyFrameIdx < 0 || keyFrameIdx > targetIndex) return result;

    for (qint64 idx = keyFrameIdx; idx <= targetIndex; idx++) {
        auto it = m_indexMap.find(idx);
        if (it != m_indexMap.end()) {
            const FrameEntry &entry = m_index[it.value()];
            if (m_dataPtr && entry.dataSize > 0) {
                QByteArray data(reinterpret_cast<const char*>(m_dataPtr + entry.dataOffset),
                                static_cast<int>(entry.dataSize));
                result.append({data, idx});
            }
        }
    }

    return result;
}

qint64 NaluFrameStore::oldestIndex() const
{
    QMutexLocker lock(&m_mutex);
    if (m_count == 0) return -1;

    qint64 oldest = INT64_MAX;
    for (auto it = m_indexMap.begin(); it != m_indexMap.end(); ++it) {
        if (it.key() < oldest) oldest = it.key();
    }
    return oldest == INT64_MAX ? -1 : oldest;
}

qint64 NaluFrameStore::newestIndex() const
{
    QMutexLocker lock(&m_mutex);
    if (m_count == 0) return -1;
    int pos = (m_head - 1 + m_capacity) % m_capacity;
    return m_index[pos].frameIndex;
}

int NaluFrameStore::count() const
{
    QMutexLocker lock(&m_mutex);
    return m_count;
}

int NaluFrameStore::registerValidRange(qint64 startIndex, qint64 endIndex)
{
    QMutexLocker lock(&m_mutex);
    int id = m_nextRangeId++;
    m_validRanges[id] = {startIndex, endIndex};
    qDebug() << "NaluFrameStore: validRange" << id << ":" << startIndex << "-" << endIndex;
    return id;
}

void NaluFrameStore::unregisterValidRange(int rangeId)
{
    QMutexLocker lock(&m_mutex);
    m_validRanges.remove(rangeId);
}

void NaluFrameStore::clearAllValidRanges()
{
    QMutexLocker lock(&m_mutex);
    m_validRanges.clear();
}

bool NaluFrameStore::isProtected(qint64 frameIndex) const
{
    for (auto it = m_validRanges.begin(); it != m_validRanges.end(); ++it) {
        if (frameIndex >= it.value().first && frameIndex <= it.value().second) {
            return true;
        }
    }
    return false;
}

void NaluFrameStore::clear()
{
    QMutexLocker lock(&m_mutex);
    for (int i = 0; i < m_capacity; i++) {
        m_index[i] = FrameEntry();
    }
    m_head = 0;
    m_count = 0;
    m_indexMap.clear();
    m_keyFrameList.clear();
    m_validRanges.clear();
    m_nextRangeId = 0;
    m_dataWritePos = 0;
}

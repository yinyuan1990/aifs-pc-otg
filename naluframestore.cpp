#include "naluframestore.h"
#include <QDebug>
#include <algorithm>

NaluFrameStore::NaluFrameStore(int capacity, QObject *parent)
    : QObject(parent)
    , m_capacity(capacity)
{
    m_buffer.resize(capacity);
    qDebug() << "NaluFrameStore: capacity" << capacity
             << "~" << (capacity * 30 / 1024) << "MB estimated";
}

NaluFrameStore::~NaluFrameStore() = default;

void NaluFrameStore::addFrame(const QByteArray &naluData, qint64 frameIndex, bool isKeyFrame)
{
    {
        QMutexLocker lock(&m_mutex);

        if (m_count >= m_capacity) {
            FrameEntry &old = m_buffer[m_head];
            if (old.frameIndex >= 0) {
                if (isProtected(old.frameIndex)) {
                    int attempts = 0;
                    int tryHead = m_head;
                    while (attempts < m_capacity) {
                        tryHead = (tryHead + 1) % m_capacity;
                        FrameEntry &tryEntry = m_buffer[tryHead];
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

                FrameEntry &victim = m_buffer[m_head];
                if (victim.frameIndex >= 0) {
                    m_indexMap.remove(victim.frameIndex);
                    if (victim.isKeyFrame) {
                        m_keyFrameList.removeOne(victim.frameIndex);
                    }
                }
            }
        }

        FrameEntry &entry = m_buffer[m_head];
        entry.data = naluData;
        entry.frameIndex = frameIndex;
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
    return m_buffer[it.value()].data;
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
    return m_buffer[it.value()].isKeyFrame;
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
            result.append({m_buffer[it.value()].data, idx});
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
    return m_buffer[pos].frameIndex;
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
        m_buffer[i] = FrameEntry();
    }
    m_head = 0;
    m_count = 0;
    m_indexMap.clear();
    m_keyFrameList.clear();
    m_validRanges.clear();
    m_nextRangeId = 0;
}

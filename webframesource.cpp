#include "webframesource.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QDateTime>
#include <QDebug>
#include <climits>

WebFrameSource::WebFrameSource(QObject *parent)
    : QObject(parent)
{
    m_frameDir = QCoreApplication::applicationDirPath() + "/captures/webframes";
    QDir().mkpath(m_frameDir);

    // 会话前缀：避免重启后旧帧文件与新索引串号。
    m_sessionPrefix = QDateTime::currentDateTime().toString("yyyyMMddHHmmss");

    // 启动时清掉上次会话残留的 webframes（与 GstPlayer 初始化清目录一致）。
    QDir dir(m_frameDir);
    const QStringList stale = dir.entryList(QStringList() << "*.jpg", QDir::Files);
    for (const QString &f : stale) {
        QFile::remove(dir.filePath(f));
    }

    qDebug() << "✅ WebFrameSource ready, dir:" << m_frameDir << "prefix:" << m_sessionPrefix;
}

WebFrameSource::~WebFrameSource()
{
}

QString WebFrameSource::framePath(qint64 frameIndex) const
{
    return m_frameDir + QString("/%1_%2.jpg").arg(m_sessionPrefix).arg(frameIndex, 9, 10, QChar('0'));
}

QByteArray WebFrameSource::readH264Frame(qint64 frameIndex) const
{
    const QString path = framePath(frameIndex);
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return QByteArray();
    return file.readAll();
}

qint64 WebFrameSource::pushJpegFrame(const QByteArray &jpeg, qint64 frameIndex)
{
    if (jpeg.isEmpty()) return -1;

    qint64 idx = frameIndex;
    {
        QMutexLocker lock(&m_mutex);
        if (idx < 0) {
            idx = m_nextIndex.fetch_add(1, std::memory_order_acq_rel);
        } else {
            // 显式索引：推进自增游标，保证后续自增不回退。
            if (idx >= m_nextIndex.load(std::memory_order_acquire)) {
                m_nextIndex.store(idx + 1, std::memory_order_release);
            }
        }
    }

    // 落盘（原子写：tmp → rename），与 GstPlayer::writeH264Frame 同口径。
    QDir().mkpath(m_frameDir);
    const QString path = framePath(idx);
    const QString tmpPath = path + ".tmp";
    QFile::remove(tmpPath);
    {
        QFile file(tmpPath);
        if (!file.open(QIODevice::WriteOnly)) {
            qWarning() << "WebFrameSource: open tmp fail" << tmpPath;
            return -1;
        }
        if (file.write(jpeg) != jpeg.size()) {
            file.close();
            QFile::remove(tmpPath);
            return -1;
        }
        file.close();
    }
    QFile::remove(path);
    if (!QFile::rename(tmpPath, path)) {
        QFile::remove(tmpPath);
        return -1;
    }

    {
        QMutexLocker lock(&m_mutex);
        m_available.insert(idx);
        const qint64 oldNewest = m_newest.load(std::memory_order_acquire);
        if (idx > oldNewest) m_newest.store(idx, std::memory_order_release);
        if (m_oldest.load(std::memory_order_acquire) < 0) {
            m_oldest.store(idx, std::memory_order_release);
        }
        // 缓存最近一帧供 grabCurrentFrame 兜底（轻量：只在解析成功时存）。
        QImage img;
        if (img.loadFromData(jpeg, "JPEG")) m_lastFrame = img;

        cleanupFramesLocked();
    }

    emit h264FrameStored(idx);
    return idx;
}

int WebFrameSource::registerH264ValidRange(qint64 start, qint64 end)
{
    QMutexLocker lock(&m_mutex);
    const int id = m_nextValidRangeId++;
    m_validRanges.insert(id, qMakePair(start, end));
    return id;
}

void WebFrameSource::updateH264ValidRange(int id, qint64 start, qint64 end)
{
    QMutexLocker lock(&m_mutex);
    if (m_validRanges.contains(id)) {
        m_validRanges[id] = qMakePair(start, end);
        cleanupFramesLocked();
    }
}

void WebFrameSource::unregisterH264ValidRange(int id)
{
    QMutexLocker lock(&m_mutex);
    m_validRanges.remove(id);
    cleanupFramesLocked();
}

QImage WebFrameSource::grabCurrentFrame()
{
    QMutexLocker lock(&m_mutex);
    return m_lastFrame;
}

void WebFrameSource::reset()
{
    QStringList toDelete;
    {
        QMutexLocker lock(&m_mutex);
        for (qint64 idx : m_available) toDelete << framePath(idx);
        m_available.clear();
        m_validRanges.clear();
        m_nextValidRangeId = 1;
        m_nextIndex.store(0, std::memory_order_release);
        m_oldest.store(-1, std::memory_order_release);
        m_newest.store(-1, std::memory_order_release);
        m_lastFrame = QImage();
    }
    for (const QString &p : toDelete) {
        QFile::remove(p);
        QFile::remove(p + ".tmp");
    }
}

bool WebFrameSource::isProtectedLocked(qint64 frameIndex) const
{
    for (auto it = m_validRanges.constBegin(); it != m_validRanges.constEnd(); ++it) {
        if (frameIndex >= it.value().first && frameIndex <= it.value().second) {
            return true;
        }
    }
    return false;
}

void WebFrameSource::recomputeOldestLocked()
{
    if (m_available.isEmpty()) {
        m_oldest.store(-1, std::memory_order_release);
        return;
    }
    qint64 oldest = LLONG_MAX;
    for (qint64 idx : m_available) oldest = qMin(oldest, idx);
    m_oldest.store(oldest, std::memory_order_release);
}

void WebFrameSource::cleanupFramesLocked()
{
    const qint64 newest = m_newest.load(std::memory_order_acquire);
    if (newest < 0) return;

    const qint64 cleanupBelow = newest - FRAME_KEEP_COUNT;
    const qint64 safeBelow = newest - SAFETY_MARGIN;
    const qint64 cutoff = qMin(cleanupBelow, safeBelow);
    if (cutoff < 0) return;

    QList<qint64> toRemove;
    for (qint64 idx : m_available) {
        if (idx <= cutoff && !isProtectedLocked(idx)) {
            toRemove.append(idx);
        }
    }

    for (qint64 idx : toRemove) {
        QFile::remove(framePath(idx));
        QFile::remove(framePath(idx) + ".tmp");
        m_available.remove(idx);
    }

    if (!toRemove.isEmpty()) {
        recomputeOldestLocked();
    }
}

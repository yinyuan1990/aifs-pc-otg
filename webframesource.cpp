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

    // §23.17：写盘/删文件专用单线程（保序）。主线程（QWebChannel 回调）不再碰磁盘。
    m_ioPool.setMaxThreadCount(1);

    // §23.17：启动清理挪后台——原来在主线程枚举+逐个删除全部旧 .jpg（与 createH264FrameBranch
    //   同款冻结源）。只删「非本会话前缀」文件，与本会话并发写入天然无冲突。
    {
        const QString dir = m_frameDir;
        const QString keepPrefix = m_sessionPrefix + QStringLiteral("_");
        m_ioPool.start([dir, keepPrefix]() {
            QDir frameDir(dir);
            const QStringList stale = frameDir.entryList(QStringList() << "*.jpg", QDir::Files);
            int removed = 0;
            for (const QString &f : stale) {
                if (f.startsWith(keepPrefix)) continue;
                if (frameDir.remove(f)) removed++;
            }
            if (removed > 0) {
                qDebug() << "🗑️ WebFrameSource: 后台清理上一会话残留" << removed << "个 .jpg";
            }
        });
    }

    qDebug() << "✅ WebFrameSource ready, dir:" << m_frameDir << "prefix:" << m_sessionPrefix;
}

WebFrameSource::~WebFrameSource()
{
    // 等后台写盘任务排干，防任务回身摸已销毁的 this。
    m_ioPool.waitForDone(3000);
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

    // §23.17：写盘+JPEG解码+清理整体挪单线程后台——本函数在 Qt 主线程（QWebChannel 回调）
    //   被调，原来每帧 4 次文件系统操作 + 整帧 JPEG 解码全在主线程，磁盘忙时同款冻结。
    //   单线程池保序；h264FrameStored 在写盘成功后从后台线程发出（消费侧 CaptureManager/
    //   SlowMotionPlayer 均为 QueuedConnection，跨线程安全）。
    const int gen = m_generation.load(std::memory_order_acquire);
    m_ioPool.start([this, jpeg, idx, gen]() {
        // 落盘（原子写：tmp → rename），与 GstPlayer::writeH264Frame 同口径。
        const QString path = framePath(idx);
        const QString tmpPath = path + ".tmp";
        QFile::remove(tmpPath);
        {
            QFile file(tmpPath);
            if (!file.open(QIODevice::WriteOnly)) {
                QDir().mkpath(m_frameDir);   // 目录被外力删除时兜底重建再试一次
                if (!file.open(QIODevice::WriteOnly)) {
                    qWarning() << "WebFrameSource: open tmp fail" << tmpPath;
                    return;
                }
            }
            if (file.write(jpeg) != jpeg.size()) {
                file.close();
                QFile::remove(tmpPath);
                return;
            }
            file.close();
        }
        QFile::remove(path);
        if (!QFile::rename(tmpPath, path)) {
            QFile::remove(tmpPath);
            return;
        }

        // reset() 已换代：这帧属于上一场，删掉落盘文件、不入索引。
        if (gen != m_generation.load(std::memory_order_acquire)) {
            QFile::remove(path);
            return;
        }

        QStringList doomed;
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

            doomed = cleanupFramesLocked();
        }
        // 滚动清理的文件删除在锁外做（本来就在后台 IO 线程，顺手删）。
        for (const QString &p : doomed) {
            QFile::remove(p);
            QFile::remove(p + ".tmp");
        }

        emit h264FrameStored(idx);
    });

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
    QStringList doomed;
    {
        QMutexLocker lock(&m_mutex);
        if (m_validRanges.contains(id)) {
            m_validRanges[id] = qMakePair(start, end);
            doomed = cleanupFramesLocked();
        }
    }
    removeFilesAsync(doomed);
}

void WebFrameSource::unregisterH264ValidRange(int id)
{
    QStringList doomed;
    {
        QMutexLocker lock(&m_mutex);
        m_validRanges.remove(id);
        doomed = cleanupFramesLocked();
    }
    removeFilesAsync(doomed);
}

// §23.17：批量文件删除统一走后台 IO 线程（主线程调用方零磁盘操作）。
void WebFrameSource::removeFilesAsync(const QStringList &paths)
{
    if (paths.isEmpty()) return;
    m_ioPool.start([paths]() {
        for (const QString &p : paths) {
            QFile::remove(p);
            QFile::remove(p + ".tmp");
        }
    });
}

QImage WebFrameSource::grabCurrentFrame()
{
    QMutexLocker lock(&m_mutex);
    return m_lastFrame;
}

void WebFrameSource::reset()
{
    // §23.17：换代——在飞的写盘任务完成时发现代际已变，自行丢弃，不会把旧场帧写回索引。
    m_generation.fetch_add(1, std::memory_order_acq_rel);

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
    // §23.17：文件删除挪后台 IO 线程（reset 由主线程调，批量删除同款冻结源）。
    if (!toDelete.isEmpty()) {
        m_ioPool.start([toDelete]() {
            for (const QString &p : toDelete) {
                QFile::remove(p);
                QFile::remove(p + ".tmp");
            }
        });
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

QStringList WebFrameSource::cleanupFramesLocked()
{
    // §23.17：锁内只摘索引并返回待删路径，文件删除由调用方在锁外（后台 IO 线程）执行，
    //   避免持锁做磁盘操作（updateH264ValidRange/unregisterH264ValidRange 可能在主线程调进来）。
    QStringList doomedPaths;

    const qint64 newest = m_newest.load(std::memory_order_acquire);
    if (newest < 0) return doomedPaths;

    const qint64 cleanupBelow = newest - FRAME_KEEP_COUNT;
    const qint64 safeBelow = newest - SAFETY_MARGIN;
    const qint64 cutoff = qMin(cleanupBelow, safeBelow);
    if (cutoff < 0) return doomedPaths;

    QList<qint64> toRemove;
    for (qint64 idx : m_available) {
        if (idx <= cutoff && !isProtectedLocked(idx)) {
            toRemove.append(idx);
        }
    }

    for (qint64 idx : toRemove) {
        doomedPaths << framePath(idx);
        m_available.remove(idx);
    }

    if (!toRemove.isEmpty()) {
        recomputeOldestLocked();
    }
    return doomedPaths;
}

#include "gzlog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFile>
#include <QMutex>
#include <QMutexLocker>
#include <QQuickItem>
#include <QQuickWindow>
#include <QThread>
#include <QThreadPool>

static constexpr qint64 kMaxBytes = 20LL * 1024 * 1024;

static QMutex s_mutex;

static QThreadPool *gzPool()
{
    static QThreadPool *pool = []() {
        auto *p = new QThreadPool();
        p->setMaxThreadCount(1);
        return p;
    }();
    return pool;
}

static QElapsedTimer &gzClock()
{
    static QElapsedTimer t = []() { QElapsedTimer e; e.start(); return e; }();
    return t;
}

static QFile *gzFile()
{
    static QFile *file = nullptr;
    static bool initialized = false;
    if (initialized) return file;
    initialized = true;
    if (!QCoreApplication::instance()) return nullptr;
    file = new QFile(QCoreApplication::applicationDirPath() + "/gz.txt");
    if (file->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        file->write(QDateTime::currentDateTime().toString("[yyyy-MM-dd hh:mm:ss] ").toUtf8()
                    + "=== gz session start（截图 / 慢放滚轮切帧排查） ===\n");
        file->flush();
    }
    return file;
}

static void rotateIfNeeded(QFile *file)
{
    if (file->size() < kMaxBytes) return;
    const QString path = file->fileName();
    const QString old = QCoreApplication::applicationDirPath() + "/gz.old.txt";
    file->close();
    QFile::remove(old);
    QFile::rename(path, old);
    file->setFileName(path);
    if (file->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        file->write(QDateTime::currentDateTime().toString("[yyyy-MM-dd hh:mm:ss] ").toUtf8()
                    + "=== gz 超 20MB，旧内容在 gz.old.txt ===\n");
    }
}

void gzLog(const QString &tag, const QString &msg)
{
    const QByteArray line = (QDateTime::currentDateTime().toString("[hh:mm:ss.zzz] ")
        + "[" + tag + "][T" + QString::number(reinterpret_cast<quintptr>(QThread::currentThreadId()), 16)
        + "] " + msg + "\n").toUtf8();
    gzPool()->start([line]() {
        QMutexLocker locker(&s_mutex);
        QFile *file = gzFile();
        if (!file || !file->isOpen()) return;
        file->write(line);
        file->flush();
        rotateIfNeeded(file);
    });
}

GzLog *GzLog::instance()
{
    static GzLog *inst = new GzLog();
    return inst;
}

double GzLog::nowMs() const
{
    return double(gzClock().nsecsElapsed()) / 1e6;
}

void GzLog::markShow(const QString &what, QObject *item)
{
    auto *quickItem = qobject_cast<QQuickItem *>(item);
    QQuickWindow *window = quickItem ? quickItem->window() : nullptr;
    if (!window) return;
    attachWindow(window);

    QMutexLocker locker(&m_showMutex);
    if (m_marks.size() >= 16) m_marks.removeFirst();   // 窗口没在画（最小化等）时别越攒越多
    ShowMark mark;
    mark.what = what;
    mark.window = window;
    mark.t0Ns = gzClock().nsecsElapsed();
    m_marks.append(mark);
    m_markCount.store(int(m_marks.size()), std::memory_order_release);
}

void GzLog::attachWindow(QQuickWindow *window)
{
    if (m_windows.contains(window)) return;
    m_windows.insert(window);
    connect(window, &QObject::destroyed, this, [this, window]() { m_windows.remove(window); });

    // 下面两个回调在渲染线程上直接跑（DirectConnection），所以只做极少的事
    // ① 场景同步完：此前登记的换图已经交给渲染线程
    connect(window, &QQuickWindow::afterSynchronizing, this, [this, window]() {
        if (m_markCount.load(std::memory_order_acquire) == 0) return;
        QMutexLocker locker(&m_showMutex);
        for (ShowMark &m : m_marks) {
            if (m.window == window) m.synced = true;
        }
    }, Qt::DirectConnection);
    // ② 这一帧画完并交换缓冲：已同步的换图算上屏，写日志
    connect(window, &QQuickWindow::frameSwapped, this, [this, window]() {
        if (m_markCount.load(std::memory_order_acquire) == 0) return;
        QVector<ShowMark> done;
        {
            QMutexLocker locker(&m_showMutex);
            for (int i = 0; i < m_marks.size(); ) {
                if (m_marks[i].window == window && m_marks[i].synced) done.append(m_marks.takeAt(i));
                else ++i;
            }
            m_markCount.store(int(m_marks.size()), std::memory_order_release);
        }
        const qint64 nowNs = gzClock().nsecsElapsed();
        for (const ShowMark &m : done) {
            gzLog("SHOW", QString("%1 换图到上屏 %2ms").arg(m.what).arg((nowNs - m.t0Ns) / 1000000));
        }
    }, Qt::DirectConnection);
}

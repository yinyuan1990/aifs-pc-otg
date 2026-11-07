#include "gzlog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFile>
#include <QMutex>
#include <QMutexLocker>
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

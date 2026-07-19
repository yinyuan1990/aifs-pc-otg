#include "capturedebuglog.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QMutex>
#include <QMutexLocker>
#include <QTextStream>
#include <QThread>
#include <QThreadPool>

static QMutex s_logMutex;

// §23.19：写盘挪单线程后台池（FIFO 保序）。原来在调用线程同步 write+flush——截图链路
// 在主线程调用多次，磁盘忙时单次 flush 可挂主线程数百 ms~1s（与 phoenix 日志 §23.11 同款）。
static QThreadPool *captureLogPool()
{
    static QThreadPool *pool = []() {
        auto *p = new QThreadPool();
        p->setMaxThreadCount(1);
        return p;
    }();
    return pool;
}

static QFile *debugLogFile()
{
    static QFile *file = nullptr;
    static bool initialized = false;
    if (initialized) {
        return file;
    }
    initialized = true;

    if (!QCoreApplication::instance()) {
        return nullptr;
    }

    file = new QFile(QCoreApplication::applicationDirPath() + "/capture_debug.txt");
    if (file->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        QTextStream s(file);
        s << QDateTime::currentDateTime().toString("[yyyy-MM-dd hh:mm:ss] ")
          << "=== capture_debug session start ===\n";
        s.flush();
    }
    return file;
}

void captureDebugLog(const QString &tag, const QString &msg)
{
    // 调用线程只做字符串拼接+入队（时间戳/线程号在调用时取，保真）；写盘在后台单线程。
    const QString line = QDateTime::currentDateTime().toString("[hh:mm:ss.zzz] ")
        + "[" + tag + "][T" + captureDebugThreadTag() + "] " + msg + "\n";
    captureLogPool()->start([line]() {
        QMutexLocker locker(&s_logMutex);
        QFile *file = debugLogFile();
        if (!file || !file->isOpen()) {
            return;
        }
        QTextStream s(file);
        s << line;
        s.flush();
    });
}

QString captureDebugThreadTag()
{
    return QString::number(reinterpret_cast<quintptr>(QThread::currentThreadId()), 16);
}

QString captureDebugNaluPreview(const QByteArray &data, int maxBytes)
{
    if (data.isEmpty()) {
        return "empty";
    }

    const int n = qMin(maxBytes, data.size());
    QString hex;
    hex.reserve(n * 3);
    for (int i = 0; i < n; ++i) {
        hex += QString("%1 ").arg(static_cast<quint8>(data.at(i)), 2, 16, QChar('0'));
    }

    int nalType = -1;
    const quint8 *p = reinterpret_cast<const quint8 *>(data.constData());
    for (int i = 0; i + 4 < data.size(); ++i) {
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1) {
            nalType = p[i + 4] & 0x1F;
            break;
        }
        if (i + 3 < data.size() && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
            nalType = p[i + 3] & 0x1F;
            break;
        }
    }

    return QString("size=%1 hex=%2 nalType=%3")
        .arg(data.size())
        .arg(hex.trimmed())
        .arg(nalType);
}

bool captureDebugAnnexBHasNalType(const QByteArray &data, quint8 nalType)
{
    const quint8 *p = reinterpret_cast<const quint8 *>(data.constData());
    const int size = data.size();
    for (int i = 0; i + 4 < size; ++i) {
        if (p[i] == 0 && p[i + 1] == 0 &&
            ((p[i + 2] == 0 && p[i + 3] == 1) || p[i + 2] == 1)) {
            const int nalIndex = (p[i + 2] == 1) ? (i + 3) : (i + 4);
            if (nalIndex < size && (p[nalIndex] & 0x1F) == nalType) {
                return true;
            }
        }
    }
    return false;
}

int captureDebugAnnexBFirstNalType(const QByteArray &data)
{
    const quint8 *p = reinterpret_cast<const quint8 *>(data.constData());
    for (int i = 0; i + 4 < data.size(); ++i) {
        if (p[i] == 0 && p[i + 1] == 0 &&
            ((p[i + 2] == 0 && p[i + 3] == 1) || p[i + 2] == 1)) {
            const int nalIndex = (p[i + 2] == 1) ? (i + 3) : (i + 4);
            if (nalIndex < data.size()) {
                return p[nalIndex] & 0x1F;
            }
        }
    }
    return -1;
}

CaptureDebugScope::CaptureDebugScope(const QString &tag, const QString &label, int warnThresholdMs)
    : m_tag(tag)
    , m_startLabel(label)
    , m_warnThresholdMs(warnThresholdMs)
{
    m_timer.start();
    captureDebugLog(tag, QString("BEGIN %1").arg(label));
}

CaptureDebugScope::~CaptureDebugScope()
{
    const qint64 ms = elapsedMs();
    const QString level = ms >= m_warnThresholdMs ? "END SLOW" : "END";
    captureDebugLog(m_tag, QString("%1 %2 took %3ms").arg(level, m_startLabel).arg(ms));
}

void CaptureDebugScope::checkpoint(const QString &label)
{
    captureDebugLog(m_tag, QString("  .. %1 +%2ms").arg(label).arg(elapsedMs()));
}

qint64 CaptureDebugScope::elapsedMs() const
{
    return m_timer.elapsed();
}

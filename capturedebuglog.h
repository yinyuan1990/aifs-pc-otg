#ifndef CAPTUREDEBUGLOG_H
#define CAPTUREDEBUGLOG_H

#include <QByteArray>
#include <QString>
#include <QElapsedTimer>

// 写入 exe 同级 capture_debug.txt，用于定位截图/慢放卡顿与切帧问题
void captureDebugLog(const QString &tag, const QString &msg);

// 作用域计时：析构时若超过 thresholdMs 则写 WARN 日志
class CaptureDebugScope
{
public:
    CaptureDebugScope(const QString &tag, const QString &label, int warnThresholdMs = 50);
    ~CaptureDebugScope();

    void checkpoint(const QString &label);
    qint64 elapsedMs() const;

private:
    QString m_tag;
    QString m_startLabel;
    int m_warnThresholdMs;
    QElapsedTimer m_timer;
};

QString captureDebugThreadTag();
QString captureDebugNaluPreview(const QByteArray &data, int maxBytes = 16);
bool captureDebugAnnexBHasNalType(const QByteArray &data, quint8 nalType);
int captureDebugAnnexBFirstNalType(const QByteArray &data);

#endif // CAPTUREDEBUGLOG_H

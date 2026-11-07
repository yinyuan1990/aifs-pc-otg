#pragma once

#include <QObject>
#include <QString>

// gz.txt：截图 / 慢放滚轮切帧专用排查日志（exe 同级，每次启动清空，超 20MB 转存 gz.old.txt 后重写）
void gzLog(const QString &tag, const QString &msg);

class GzLog : public QObject
{
    Q_OBJECT
public:
    static GzLog *instance();

    Q_INVOKABLE void log(const QString &tag, const QString &msg) { gzLog(tag, msg); }
    // 毫秒单调时钟，QML 算「滚轮 → 出图」耗时用
    Q_INVOKABLE double nowMs() const;

private:
    explicit GzLog(QObject *parent = nullptr) : QObject(parent) {}
};

#pragma once

#include <QObject>
#include <QString>
#include <QMutex>
#include <QSet>
#include <QVector>
#include <atomic>

class QQuickWindow;

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
    // 界面换图时调一次（item = 换图的那个界面元素，用它找到所在窗口）：记下时间，等这次改动同步给
    // 渲染线程、画完并交给屏幕后写一条「SHOW … 换图到上屏 Nms」（含取图、传显卡、渲染）。
    // 没有待记的换图时，渲染线程每帧只多读一个原子数。
    Q_INVOKABLE void markShow(const QString &what, QObject *item);

private:
    explicit GzLog(QObject *parent = nullptr) : QObject(parent) {}
    void attachWindow(QQuickWindow *window);

    struct ShowMark {
        QString what;
        QQuickWindow *window = nullptr;
        qint64 t0Ns = 0;
        bool synced = false;   // 这次换图已经同步给渲染线程（下一次交换缓冲就是它上屏）
    };
    QSet<QQuickWindow *> m_windows;   // 只在主线程用
    QMutex m_showMutex;
    QVector<ShowMark> m_marks;        // m_showMutex 保护
    std::atomic<int> m_markCount{0};
};

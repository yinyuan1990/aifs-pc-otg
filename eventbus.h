#ifndef EVENTBUS_H
#define EVENTBUS_H

#include <QObject>

/**
 * 全局事件总线 - 解耦各组件间的通信
 * 
 * 使用方式：
 * - QML: EventBus.captureTriggered()
 * - C++: connect(EventBus::instance(), &EventBus::captureTriggered, ...)
 */
class EventBus : public QObject
{
    Q_OBJECT

public:
    static EventBus* instance();
    
    // 供 QML 调用的触发方法
    Q_INVOKABLE void triggerCapture();
    Q_INVOKABLE void triggerClear();
    Q_INVOKABLE void triggerStop();
    Q_INVOKABLE void triggerSlowmoToggle();
    Q_INVOKABLE void triggerNextFrame();
    Q_INVOKABLE void triggerPrevFrame();

signals:
    // 抓拍事件
    void captureTriggered();
    void clearTriggered();
    void stopTriggered();
    
    // 慢放事件
    void slowmoToggleTriggered();
    void nextFrameTriggered();
    void prevFrameTriggered();

private:
    explicit EventBus(QObject *parent = nullptr);
    static EventBus *s_instance;
};

#endif // EVENTBUS_H


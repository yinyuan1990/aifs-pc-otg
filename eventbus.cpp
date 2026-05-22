#include "eventbus.h"
#include <QDebug>

EventBus* EventBus::s_instance = nullptr;

EventBus* EventBus::instance()
{
    if (!s_instance) {
        s_instance = new EventBus();
    }
    return s_instance;
}

EventBus::EventBus(QObject *parent)
    : QObject(parent)
{
    qDebug() << "EventBus singleton created";
}

void EventBus::triggerCapture()
{
    qDebug() << "EventBus: triggerCapture()";
    emit captureTriggered();
}

void EventBus::triggerClear()
{
    emit clearTriggered();
}

void EventBus::triggerStop()
{
    emit stopTriggered();
}

void EventBus::triggerSlowmoToggle()
{
    emit slowmoToggleTriggered();
}

void EventBus::triggerNextFrame()
{
    emit nextFrameTriggered();
}

void EventBus::triggerPrevFrame()
{
    emit prevFrameTriggered();
}


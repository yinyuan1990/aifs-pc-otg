#include "imageprovider.h"
#include "capturemanager.h"
#include "slowmotionplayer.h"
#include <QDebug>

// 静态实例
CaptureImageProvider* CaptureImageProvider::s_instance = nullptr;

CaptureImageProvider* CaptureImageProvider::instance()
{
    return s_instance;
}

CaptureImageProvider::CaptureImageProvider()
    : QQuickImageProvider(QQuickImageProvider::Image)
{
    s_instance = this;
}

void CaptureImageProvider::setCaptureManager(CaptureManager *manager)
{
    m_captureManager = manager;
}

void CaptureImageProvider::setSlowMotionPlayer(SlowMotionPlayer *player)
{
    m_slowMotionPlayer = player;
}

QImage CaptureImageProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    QImage result;
    
    QStringList parts = id.split('/');
    
    if (parts.isEmpty()) {
        return result;
    }
    
    QString type = parts[0];
    
    if (type == "thumbnail" && parts.size() >= 2 && m_captureManager) {
        // 缩略图: image://capture/thumbnail/<itemIndex>
        // 显示事件帧作为缩略图
        int itemIndex = parts[1].toInt();
        int eventOffset = m_captureManager->getEventOffset(itemIndex);
        result = m_captureManager->getFrameImage(itemIndex, eventOffset);
    }
    else if (type == "frame" && parts.size() >= 3 && m_captureManager) {
        // 帧: image://capture/frame/<itemIndex>/<frameOffset>?zoom=xxx
        int itemIndex = parts[1].toInt();
        // 去掉查询参数（?zoom=xxx）
        QString frameStr = parts[2].split('?').first();
        int frameOffset = frameStr.toInt();
        
        // ⭐⭐⭐ 诊断日志：QML请求的帧 vs CaptureManager记录的当前帧
        int storedCurrentOffset = m_captureManager->getCurrentOffset(itemIndex);
        qDebug() << "🔍 ImageProvider | item=" << itemIndex 
                 << "| QML请求frameOffset=" << frameOffset
                 << "| Manager.currentOffset=" << storedCurrentOffset
                 << "| 是否同步=" << (frameOffset == storedCurrentOffset ? "✅" : "❌不同步!");
        
        result = m_captureManager->getFrameImage(itemIndex, frameOffset);
    }
    else if (type == "slowmo") {
        // 慢放帧: image://capture/slowmo/<frameOffset>_<timestamp>
        if (!m_slowMotionPlayer) {
            qDebug() << "ImageProvider: slowmo request but no player set";
        } else {
            int frameOffset = m_slowMotionPlayer->currentFrame();
            if (parts.size() >= 2) {
                // 解析 "frameOffset_timestamp" 格式，只取下划线前的部分
                QString framePart = parts[1].split('_').first();
                frameOffset = framePart.toInt();
            }
            qDebug() << "ImageProvider: slowmo request, frameOffset:" << frameOffset;
            result = m_slowMotionPlayer->getFrameImage(frameOffset);
            qDebug() << "ImageProvider: slowmo result:" << (result.isNull() ? "null" : "ok") 
                     << result.size();
        }
    }
    
    if (result.isNull()) {
        // 返回空白图像
        QSize defaultSize = requestedSize.isValid() ? requestedSize : QSize(160, 120);
        result = QImage(defaultSize, QImage::Format_RGB32);
        result.fill(Qt::darkGray);
    }
    
    if (size) {
        *size = result.size();
    }
    
    // 缩放
    if (requestedSize.isValid() && requestedSize != result.size()) {
        result = result.scaled(requestedSize, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }
    
    return result;
}

#include "imageprovider.h"
#include "capturemanager.h"
#include "slowmotionplayer.h"
#include "capturedebuglog.h"
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
    CaptureDebugScope scope("IMG", QString("requestImage id=%1").arg(id), 80);

    QImage result;
    
    QStringList parts = id.split('/');
    
    if (parts.isEmpty()) {
        return result;
    }
    
    QString type = parts[0];
    
    if (type == "thumbnail" && parts.size() >= 2 && m_captureManager) {
        int itemIndex = parts[1].toInt();
        int eventOffset = m_captureManager->getEventOffset(itemIndex);
        scope.checkpoint(QString("thumbnail item=%1 event=%2").arg(itemIndex).arg(eventOffset));
        result = m_captureManager->getFrameImage(itemIndex, eventOffset);
    }
    else if (type == "frame" && parts.size() >= 3 && m_captureManager) {
        int itemIndex = parts[1].toInt();
        QString frameStr = parts[2].split('?').first();
        int frameOffset = frameStr.toInt();
        
        int storedCurrentOffset = m_captureManager->getCurrentOffset(itemIndex);
        captureDebugLog("IMG", QString("frame item=%1 req=%2 manager=%3 sync=%4")
            .arg(itemIndex).arg(frameOffset).arg(storedCurrentOffset)
            .arg(frameOffset == storedCurrentOffset ? "Y" : "N"));

        result = m_captureManager->getFrameImage(itemIndex, frameOffset);
    }
    else if (type == "slowmo") {
        if (!m_slowMotionPlayer) {
            captureDebugLog("IMG", "slowmo no player");
        } else {
            int frameOffset = m_slowMotionPlayer->currentFrame();
            if (parts.size() >= 2) {
                QString framePart = parts[1].split('_').first();
                frameOffset = framePart.toInt();
            }
            captureDebugLog("IMG", QString("slowmo frame=%1").arg(frameOffset));
            result = m_slowMotionPlayer->getFrameImage(frameOffset);
        }
    } else {
        captureDebugLog("IMG", QString("unknown type=%1 parts=%2").arg(type).arg(parts.size()));
    }
    
    if (result.isNull()) {
        captureDebugLog("IMG", QString("NULL result id=%1 -> gray placeholder").arg(id));
        QSize defaultSize = requestedSize.isValid() ? requestedSize : QSize(160, 120);
        result = QImage(defaultSize, QImage::Format_RGB32);
        result.fill(Qt::darkGray);
    }
    
    if (size) {
        *size = result.size();
    }
    
    if (requestedSize.isValid() && requestedSize != result.size()) {
        result = result.scaled(requestedSize, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }

    captureDebugLog("IMG", QString("done id=%1 out=%2x%3").arg(id).arg(result.width()).arg(result.height()));
    return result;
}

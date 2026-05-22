#include "videoplayer.h"
#include <QPainter>
#include <QDebug>

VideoPlayer::VideoPlayer(QQuickItem *parent)
    : QQuickPaintedItem(parent)
{
    qDebug() << "📦 VideoPlayer 构造（简化版，仅支持外部图像）";
    
    // 设置渲染模式
    setRenderTarget(QQuickPaintedItem::FramebufferObject);
    setAntialiasing(false);
}

VideoPlayer::~VideoPlayer()
{
    stop();
}

void VideoPlayer::paint(QPainter *painter)
{
    QMutexLocker locker(&m_frameMutex);
    
    if (m_currentFrame.isNull()) {
        // 绘制黑色背景
        painter->fillRect(boundingRect(), Qt::black);
        
        // 绘制状态文字
        painter->setPen(Qt::white);
        painter->drawText(boundingRect(), Qt::AlignCenter, m_status);
        return;
    }
    
    // 计算保持宽高比的绘制区域
    QRectF targetRect = boundingRect();
    QSizeF imageSize = m_currentFrame.size();
    QSizeF scaledSize = imageSize.scaled(targetRect.size(), Qt::KeepAspectRatio);
    
    QRectF drawRect(
        (targetRect.width() - scaledSize.width()) / 2,
        (targetRect.height() - scaledSize.height()) / 2,
        scaledSize.width(),
        scaledSize.height()
    );
    
    // 填充黑色背景
    painter->fillRect(targetRect, Qt::black);
    
    // 绘制视频帧
    painter->drawImage(drawRect, m_currentFrame);
}

void VideoPlayer::setSource(const QString &source)
{
    if (m_source != source) {
        m_source = source;
        emit sourceChanged();
    }
}

void VideoPlayer::play()
{
    m_playing = true;
    m_status = "Playing";
    emit playingChanged();
    emit statusChanged();
}

void VideoPlayer::stop()
{
    m_playing = false;
    m_status = "Stopped";
    m_externalFrameCount = 0;
    emit playingChanged();
    emit statusChanged();
    
    // 清空画面
    {
        QMutexLocker locker(&m_frameMutex);
        m_currentFrame = QImage();
    }
    update();
}

void VideoPlayer::displayImage(const QImage &image)
{
    if (image.isNull()) {
        return;
    }
    
    {
        QMutexLocker locker(&m_frameMutex);
        m_currentFrame = image;
        
        // 更新外部模式的分辨率
        if (image.width() != m_externalWidth || image.height() != m_externalHeight) {
            m_externalWidth = image.width();
            m_externalHeight = image.height();
            
            // 通知分辨率变化
            QMetaObject::invokeMethod(this, [this]() {
                emit videoInfoChanged();
            }, Qt::QueuedConnection);
        }
    }
    
    m_externalFrameCount++;
    m_playing = true;
    
    // 请求重绘
    update();
}

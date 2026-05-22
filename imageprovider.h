#ifndef IMAGEPROVIDER_H
#define IMAGEPROVIDER_H

#include <QQuickImageProvider>
#include <QImage>
#include <QMutex>
#include <QHash>

class CaptureManager;
class SlowMotionPlayer;

/**
 * 抓拍图像提供者 - 为 QML 提供缩略图和帧图像
 * URL 格式：
 * - image://capture/thumbnail/<index>  - 抓拍缩略图
 * - image://capture/frame/<itemIndex>/<frameIndex>  - 抓拍帧
 */
class CaptureImageProvider : public QQuickImageProvider
{
public:
    static CaptureImageProvider* instance();
    
    CaptureImageProvider();
    
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
    
    // 设置管理器（在 QML 组件创建后调用）
    void setCaptureManager(CaptureManager *manager);
    void setSlowMotionPlayer(SlowMotionPlayer *player);

private:
    static CaptureImageProvider* s_instance;
    CaptureManager *m_captureManager = nullptr;
    SlowMotionPlayer *m_slowMotionPlayer = nullptr;
};

#endif // IMAGEPROVIDER_H

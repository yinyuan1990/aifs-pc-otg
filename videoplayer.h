#ifndef VIDEOPLAYER_H
#define VIDEOPLAYER_H

#include <QQuickPaintedItem>
#include <QImage>
#include <QMutex>

/**
 * QML 视频播放组件（简化版）
 * 仅支持外部图像模式：接收外部解码的 QImage 进行显示
 * 注：原 FFmpegDecoder 已被 GstPlayer 替代
 */
class VideoPlayer : public QQuickPaintedItem
{
    Q_OBJECT
    Q_PROPERTY(QString source READ source WRITE setSource NOTIFY sourceChanged)
    Q_PROPERTY(bool playing READ isPlaying NOTIFY playingChanged)
    Q_PROPERTY(int videoWidth READ videoWidth NOTIFY videoInfoChanged)
    Q_PROPERTY(int videoHeight READ videoHeight NOTIFY videoInfoChanged)
    Q_PROPERTY(double decodeFps READ decodeFps NOTIFY statsChanged)
    Q_PROPERTY(qint64 frameCount READ frameCount NOTIFY statsChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)

public:
    explicit VideoPlayer(QQuickItem *parent = nullptr);
    ~VideoPlayer();

    void paint(QPainter *painter) override;

    QString source() const { return m_source; }
    void setSource(const QString &source);

    bool isPlaying() const { return m_playing; }
    int videoWidth() const { return m_externalWidth; }
    int videoHeight() const { return m_externalHeight; }
    double decodeFps() const { return m_decodeFps; }
    qint64 frameCount() const { return m_externalFrameCount; }
    QString status() const { return m_status; }

    Q_INVOKABLE void play();
    Q_INVOKABLE void stop();
    
    // 显示外部图像（用于 WebRTC 解码后的帧）
    Q_INVOKABLE void displayImage(const QImage &image);

signals:
    void sourceChanged();
    void playingChanged();
    void videoInfoChanged();
    void statsChanged();
    void statusChanged();
    void errorOccurred(const QString &message);
    
    // 帧就绪信号（用于抓拍）
    void frameReady(const QImage &frame, qint64 pts, qint64 frameIndex);

private:
    QString m_source;
    bool m_playing = false;
    double m_decodeFps = 0;
    QString m_status = "Ready";
    
    QImage m_currentFrame;
    QMutex m_frameMutex;
    
    // 外部图像模式
    int m_externalWidth = 0;
    int m_externalHeight = 0;
    qint64 m_externalFrameCount = 0;
};

#endif // VIDEOPLAYER_H

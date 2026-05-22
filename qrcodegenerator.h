#ifndef QRCODEGENERATOR_H
#define QRCODEGENERATOR_H

#include <QQuickPaintedItem>
#include <QColor>
#include <QVector>

/**
 * 二维码生成器 - QML 组件
 * 使用 QQuickPaintedItem 在 QML 中渲染二维码
 */
class QRCodeGenerator : public QQuickPaintedItem
{
    Q_OBJECT
    Q_PROPERTY(QString text READ text WRITE setText NOTIFY textChanged)
    Q_PROPERTY(QColor foreground READ foreground WRITE setForeground NOTIFY foregroundChanged)
    Q_PROPERTY(QColor background READ background WRITE setBackground NOTIFY backgroundChanged)
    Q_PROPERTY(int margin READ margin WRITE setMargin NOTIFY marginChanged)
    
public:
    explicit QRCodeGenerator(QQuickItem *parent = nullptr);
    
    QString text() const { return m_text; }
    void setText(const QString &text);
    
    QColor foreground() const { return m_foreground; }
    void setForeground(const QColor &color);
    
    QColor background() const { return m_background; }
    void setBackground(const QColor &color);
    
    int margin() const { return m_margin; }
    void setMargin(int margin);
    
    void paint(QPainter *painter) override;
    
signals:
    void textChanged();
    void foregroundChanged();
    void backgroundChanged();
    void marginChanged();
    
private:
    void generateQRCode();
    
    QString m_text;
    QColor m_foreground = Qt::black;
    QColor m_background = QColor(232, 232, 232);  // 柔和浅灰色
    int m_margin = 2;
    
    // 二维码数据
    QVector<QVector<bool>> m_modules;
    int m_size = 0;
};

#endif // QRCODEGENERATOR_H


#include "qrcodegenerator.h"
#include "qrcodegen.hpp"
#include <QPainter>
#include <QDebug>

using qrcodegen::QrCode;
using qrcodegen::QrSegment;

QRCodeGenerator::QRCodeGenerator(QQuickItem *parent)
    : QQuickPaintedItem(parent)
{
    setAntialiasing(false);
}

void QRCodeGenerator::setText(const QString &text)
{
    if (m_text != text) {
        m_text = text;
        generateQRCode();
        emit textChanged();
        update();
    }
}

void QRCodeGenerator::setForeground(const QColor &color)
{
    if (m_foreground != color) {
        m_foreground = color;
        emit foregroundChanged();
        update();
    }
}

void QRCodeGenerator::setBackground(const QColor &color)
{
    if (m_background != color) {
        m_background = color;
        emit backgroundChanged();
        update();
    }
}

void QRCodeGenerator::setMargin(int margin)
{
    if (m_margin != margin) {
        m_margin = margin;
        emit marginChanged();
        update();
    }
}

void QRCodeGenerator::generateQRCode()
{
    m_modules.clear();
    m_size = 0;
    
    if (m_text.isEmpty()) {
        return;
    }
    
    try {
        // 使用 nayuki 的 qrcodegen 库生成二维码
        std::string utf8Text = m_text.toStdString();
        
        // 生成 QR Code，使用 LOW 纠错等级以获得最大容量
        QrCode qr = QrCode::encodeText(utf8Text.c_str(), QrCode::Ecc::LOW);
        
        m_size = qr.getSize();
        m_modules.resize(m_size);
        
        for (int y = 0; y < m_size; y++) {
            m_modules[y].resize(m_size);
            for (int x = 0; x < m_size; x++) {
                m_modules[y][x] = qr.getModule(x, y);
            }
        }
        
        qDebug() << "[QRCode] Generated" << m_size << "x" << m_size << "for:" << m_text;
        
    } catch (const std::exception &e) {
        qWarning() << "[QRCode] Failed to generate:" << e.what();
        m_modules.clear();
        m_size = 0;
    }
}

void QRCodeGenerator::paint(QPainter *painter)
{
    if (m_size == 0 || m_modules.isEmpty()) {
        // 没有二维码时绘制背景
        painter->fillRect(QRectF(0, 0, width(), height()), m_background);
        return;
    }
    
    int totalSize = m_size + m_margin * 2;
    qreal cellSize = qMin(width(), height()) / totalSize;
    
    if (cellSize <= 0) return;
    
    // 居中偏移
    qreal offsetX = (width() - totalSize * cellSize) / 2;
    qreal offsetY = (height() - totalSize * cellSize) / 2;
    
    // 绘制背景
    painter->fillRect(QRectF(offsetX, offsetY, totalSize * cellSize, totalSize * cellSize), m_background);
    
    // 绘制模块
    painter->setBrush(m_foreground);
    painter->setPen(Qt::NoPen);
    
    for (int y = 0; y < m_size && y < m_modules.size(); y++) {
        for (int x = 0; x < m_size && x < m_modules[y].size(); x++) {
            if (m_modules[y][x]) {
                qreal px = offsetX + (x + m_margin) * cellSize;
                qreal py = offsetY + (y + m_margin) * cellSize;
                painter->drawRect(QRectF(px, py, cellSize + 0.5, cellSize + 0.5));  // +0.5 避免间隙
            }
        }
    }
}

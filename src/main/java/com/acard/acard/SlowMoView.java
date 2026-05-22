package com.acard.acard;

import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/**
 * 慢放显示视图：在独立画布上绘制图像（fit模式），用于元素2-2。
 * ✅ 保持宽高比，完整显示，不裁剪画面
 */
public class SlowMoView extends StackPane {
    private final Canvas canvas = new Canvas();
    private volatile Image lastImage = null;

    public SlowMoView() {
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        setMinSize(0, 0);
        // ✅ 尺寸变化时自动重绘最近一帧，保持宽高比完整显示
        widthProperty().addListener((obs, ov, nv) -> rerender());
        heightProperty().addListener((obs, ov, nv) -> rerender());
    }

    public void renderImage(Image img) {
        lastImage = img; // 记录最近图像，便于在父容器尺寸变化时重绘
        double containerW = getWidth();
        double containerH = getHeight();
        if (containerW <= 0 || containerH <= 0 || img == null) return;
        
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, containerW, containerH);
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, containerW, containerH);
        gc.setImageSmoothing(true);
        
        // ✅ 保持宽高比，完整显示（fit模式）：不管怎么拉窗口都能看到完整画面
        double imgW = img.getWidth();
        double imgH = img.getHeight();
        if (imgW <= 0 || imgH <= 0) return;
        
        double imgRatio = imgW / imgH;
        double containerRatio = containerW / containerH;
        
        double drawW, drawH, drawX, drawY;
        if (imgRatio > containerRatio) {
            // 图片更宽，以容器宽度为基准
            drawW = containerW;
            drawH = containerW / imgRatio;
            drawX = 0;
            drawY = (containerH - drawH) / 2;  // 垂直居中
        } else {
            // 图片更高，以容器高度为基准
            drawH = containerH;
            drawW = containerH * imgRatio;
            drawX = (containerW - drawW) / 2;  // 水平居中
            drawY = 0;
        }
        
        gc.drawImage(img, drawX, drawY, drawW, drawH);  // ✅ 居中显示，保持宽高比
    }
    
    /**
     * 清空画面（显示黑屏）
     */
    public void clearImage() {
        lastImage = null;
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, w, h);
    }

    private void rerender() {
        if (lastImage != null) {
            renderImage(lastImage);
        } else {
            var gc = canvas.getGraphicsContext2D();
            gc.clearRect(0, 0, getWidth(), getHeight());
        }
    }

    public void clear() {
        // ✅ 清理最后一帧，释放内存
        if (lastImage != null) {
            lastImage = null;
            System.gc();  // 建议GC立即回收（Image对象很大，值得主动触发）
        }
        
        var gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());
    }
}
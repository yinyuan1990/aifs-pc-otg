package com.acard.acard.ui.dialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 二维码弹窗对话框
 * 用于显示设备绑定二维码
 */
public class QRCodeDialog {
    
    private Stage dialogStage;
    private String qrContent;
    private int qrSize;
    private ImageView qrImageView;
    
    /**
     * 构造函数
     * @param content 二维码内容
     * @param size 二维码尺寸
     */
    public QRCodeDialog(String content, int size) {
        this.qrContent = content;
        this.qrSize = size;
        initializeDialog();
    }
    
    /**
     * 初始化对话框
     */
    private void initializeDialog() {
        dialogStage = new Stage();
        dialogStage.initStyle(StageStyle.UNDECORATED);
        dialogStage.initModality(Modality.NONE); // 改为非模态，允许点击外部关闭
        
        // 创建二维码容器
        StackPane qrContainer = new StackPane();
        qrContainer.setPadding(new Insets(20));
        qrContainer.setStyle("-fx-background-color: white; -fx-border-color: #cccccc; -fx-border-width: 1;");
        
        // 创建ImageView显示二维码
        qrImageView = new ImageView();
        qrImageView.setFitWidth(qrSize);
        qrImageView.setFitHeight(qrSize);
        qrImageView.setPreserveRatio(true);
        
        qrContainer.getChildren().add(qrImageView);
        
        // 点击二维码容器关闭弹框
        qrContainer.setOnMouseClicked(event -> close());
        
        // 创建场景
        Scene scene = new Scene(qrContainer);
        dialogStage.setScene(scene);
        
        // 添加失去焦点时自动关闭功能
        dialogStage.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) {
                close();
            }
        });
    }
    
    /**
     * 生成二维码图像
     * @return Image 二维码图像
     */
    private Image generateQRCodeImage() {
        try {
            // 使用ZXing生成二维码
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(qrContent, BarcodeFormat.QR_CODE, qrSize, qrSize);
            
            // 转换为BufferedImage
            BufferedImage bufferedImage = new BufferedImage(qrSize, qrSize, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    bufferedImage.setRGB(x, y, bitMatrix.get(x, y) ? java.awt.Color.BLACK.getRGB() : java.awt.Color.WHITE.getRGB());
                }
            }
            
            // 转换为JavaFX Image
            Image qrImage = SwingFXUtils.toFXImage(bufferedImage, null);
            
            return qrImage;
            
        } catch (WriterException e) {
            // 如果生成失败，返回null
            return null;
        }
    }
    
    /**
     * 显示对话框
     * @param owner 父窗口
     */
    public void show(Stage owner) {
        if (owner != null) {
            dialogStage.initOwner(owner);
            // 定位在父窗口中心
            dialogStage.setX(owner.getX() + (owner.getWidth() - dialogStage.getWidth()) / 2);
            dialogStage.setY(owner.getY() + (owner.getHeight() - dialogStage.getHeight()) / 2);
        }
        // 生成二维码图像
        Image qrImage = generateQRCodeImage();
        qrImageView.setImage(qrImage);
        dialogStage.show();
    }
    
    /**
     * 显示对话框并定位在指定按钮下方
     * @param owner 父窗口
     * @param buttonX 按钮X坐标
     * @param buttonY 按钮Y坐标
     * @param buttonWidth 按钮宽度
     * @param buttonHeight 按钮高度
     */
    public void showBelowButton(Stage owner, double buttonX, double buttonY, double buttonWidth, double buttonHeight) {
        if (owner != null) {
            dialogStage.initOwner(owner);
        }
        
        // 生成二维码图像
        Image qrImage = generateQRCodeImage();
        qrImageView.setImage(qrImage);
        
        // 先显示对话框以获取其尺寸
        dialogStage.show();
        
        // 计算位置：按钮下方居中
        double dialogX = owner.getX() + buttonX + (buttonWidth - dialogStage.getWidth()) / 2;
        double dialogY = owner.getY() + buttonY + buttonHeight + 5; // 5px间距
        
        // 确保对话框不超出屏幕边界
        if (dialogX < 0) dialogX = 0;
        if (dialogY < 0) dialogY = 0;
        
        dialogStage.setX(dialogX);
        dialogStage.setY(dialogY);
    }
    
    /**
     * 关闭对话框
     */
    public void close() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
    
    /**
     * 获取对话框Stage
     * @return Stage
     */
    public Stage getStage() {
        return dialogStage;
    }
}
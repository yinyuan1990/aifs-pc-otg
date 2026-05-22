package com.acard.acard;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 启动画面（Splash Screen）
 * 
 * 使用 Swing 实现，比 JavaFX 启动快很多
 * 在应用启动时立即显示，让用户知道程序正在加载
 */
public class SplashScreen {
    
    private JFrame splashFrame;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private volatile boolean isVisible = false;
    
    // 单例
    private static volatile SplashScreen instance;
    
    public static SplashScreen getInstance() {
        if (instance == null) {
            synchronized (SplashScreen.class) {
                if (instance == null) {
                    instance = new SplashScreen();
                }
            }
        }
        return instance;
    }
    
    private SplashScreen() {
        // 在 EDT 中创建 UI
        try {
            SwingUtilities.invokeAndWait(this::createUI);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void createUI() {
        splashFrame = new JFrame();
        splashFrame.setUndecorated(true);  // 无边框
        splashFrame.setAlwaysOnTop(true);   // 始终在最前
        
        // 主面板
        JPanel mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // 渐变背景
                GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(30, 30, 35),
                    0, getHeight(), new Color(45, 45, 55)
                );
                g2d.setPaint(gradient);
                g2d.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 20, 20));
                
                // 边框
                g2d.setColor(new Color(80, 80, 90));
                g2d.setStroke(new BasicStroke(1));
                g2d.draw(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 20, 20));
                
                g2d.dispose();
            }
        };
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(40, 50, 40, 50));
        mainPanel.setOpaque(false);
        
        // Logo / 标题
        JLabel titleLabel = new JLabel("Acard");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 42));
        titleLabel.setForeground(new Color(66, 165, 245));  // 蓝色
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // 副标题
        JLabel subtitleLabel = new JLabel("Secure Vision System");
        subtitleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        subtitleLabel.setForeground(new Color(180, 180, 190));
        subtitleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // 版本号
        JLabel versionLabel = new JLabel("v1.0.0");
        versionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        versionLabel.setForeground(new Color(120, 120, 130));
        versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // 进度条
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(300, 8));
        progressBar.setMaximumSize(new Dimension(300, 8));
        progressBar.setBorderPainted(false);
        progressBar.setStringPainted(false);
        progressBar.setForeground(new Color(66, 165, 245));
        progressBar.setBackground(new Color(60, 60, 70));
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // 状态标签
        statusLabel = new JLabel("正在启动...");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(150, 150, 160));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // 添加组件
        mainPanel.add(Box.createVerticalGlue());
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(8));
        mainPanel.add(subtitleLabel);
        mainPanel.add(Box.createVerticalStrut(4));
        mainPanel.add(versionLabel);
        mainPanel.add(Box.createVerticalStrut(40));
        mainPanel.add(progressBar);
        mainPanel.add(Box.createVerticalStrut(12));
        mainPanel.add(statusLabel);
        mainPanel.add(Box.createVerticalGlue());
        
        splashFrame.setContentPane(mainPanel);
        splashFrame.setSize(400, 280);
        splashFrame.setLocationRelativeTo(null);  // 居中
        
        // 设置圆角窗口（Windows 10+）
        try {
            splashFrame.setShape(new RoundRectangle2D.Double(0, 0, 400, 280, 20, 20));
        } catch (Exception e) {
            // 不支持圆角，忽略
        }
        
        // 设置透明背景
        splashFrame.setBackground(new Color(0, 0, 0, 0));
    }
    
    /**
     * 显示启动画面
     */
    public void show() {
        SwingUtilities.invokeLater(() -> {
            splashFrame.setVisible(true);
            isVisible = true;
        });
    }
    
    /**
     * 隐藏并销毁启动画面
     */
    public void hide() {
        SwingUtilities.invokeLater(() -> {
            splashFrame.setVisible(false);
            splashFrame.dispose();
            isVisible = false;
        });
    }
    
    /**
     * 更新进度
     * @param progress 进度值 (0-100)
     * @param status 状态文本
     */
    public void updateProgress(int progress, String status) {
        SwingUtilities.invokeLater(() -> {
            if (progressBar != null) {
                progressBar.setValue(progress);
            }
            if (statusLabel != null && status != null) {
                statusLabel.setText(status);
            }
        });
    }
    
    /**
     * 更新状态文本
     * @param status 状态文本
     */
    public void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null && status != null) {
                statusLabel.setText(status);
            }
        });
    }
    
    /**
     * 检查是否可见
     */
    public boolean isShowing() {
        return isVisible;
    }
}


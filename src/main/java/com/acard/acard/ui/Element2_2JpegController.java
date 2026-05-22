package com.acard.acard.ui;


import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.model.CaptureData;
import com.acard.acard.slowmotion.DiskJpegPlayer;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.store.GridStore;
import com.acard.acard.tools.CaptureDataManager;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import com.acard.acard.utils.GStreamerJpegScaler;
import com.acard.acard.utils.GStreamerJpegScalerV2;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 元素2-2 JPEG 序列播放器控制器
 *
 * 功能：
 * - 实时流模式（推送驱动）
 * - 回放模式（磁盘播放）
 * - 拖动预览模式
 * - 速度控制、进度条、帧数显示
 * 
 * 三种 UI 模式：
 * - REALTIME: 实时流模式，画面跟随最新推送帧
 * - PLAYBACK: 回放模式，循环播放已下载帧
 * - DRAGGING: 拖动模式，预览滑块位置帧
 */
public class Element2_2JpegController implements Initializable {

    // ========== UI 模式枚举 ==========
    private enum UIMode {
        REALTIME,   // 实时流模式：画面跟随最新帧
        PLAYBACK,   // 回放模式：循环播放已下载帧
        DRAGGING    // 拖动模式：预览滑块位置帧
    }

    @FXML private StackPane videoContainer;
    @FXML private StackPane mainContentPane;
    @FXML private ImageView imageView;
    @FXML private Button playPauseButton;
    /*@FXML private Button realtimeButton;
    @FXML private Label modeLabel;*/
    @FXML private MenuButton speedMenuButton;

    @FXML private Slider progressSlider;
    @FXML private Label positionLabel;      // 当前播放位置
    @FXML private Label downloadedLabel;    // 已下载帧数
    
    // ========== AI 视图相关 ==========
    @FXML private HBox aiControlBar;               // AI 控制栏（底部）
    @FXML private HBox slowmoControlBar;           // 慢放控制栏（底部）
    @FXML private StackPane bottomControlPane;     // 底部控制面板容器
    @FXML private MenuButton aiRowMenuButton;      // AI 行数选择
    @FXML private MenuButton aiColMenuButton;      // AI 列数选择
    @FXML private Button aiLayoutToggleButton;     // AI 横向/纵向切换
    @FXML private Button aiTestButton;             // AI 测试按钮
    @FXML private Button aiClearButton;            // AI 清空按钮
    @FXML private ScrollPane aiScroll;             // AI 网格滚动容器
    @FXML private GridPane aiGrid;                 // AI 网格

    private static final String PLAY_STYLE =
            "-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6 12;";

    private static final String PAUSE_STYLE =
            "-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6 12;";

    private int currentSpeedIndex = 9;  // 默认1x（索引9）
    
    private DiskJpegPlayer player;

    // ========== UI 状态管理 ==========
    private volatile UIMode currentUIMode = UIMode.REALTIME;  // 当前 UI 模式
    private volatile int currentPosition = 0;                  // 当前播放/预览位置（相对于 startFrame）

    // ⭐ 异步复制线程池
    private final ExecutorService copyExecutor = Executors.newSingleThreadExecutor();
    
    // ⭐ 复用WritableImage，避免内存泄漏
    private javafx.scene.image.WritableImage reusableImage = null;
    private int frameUpdateCount = 0;
    
    // ⭐ 鼠标是否在慢放区域内
    private volatile boolean mouseInSlowmoArea = false;

    // ⭐ 复制计数器
    private volatile int copiedFrameCount = 0;
    
    // ========== AI 视图状态 ==========
    private boolean isAiViewShowing = false;                    // 当前是否显示 AI 视图
    private AiGridLayoutManager aiLayoutManager;                // AI 网格布局管理器
    private final java.util.List<AiCardItem> aiCardItems = new java.util.ArrayList<>();  // AI 卡片列表
    private int aiRows;                                         // AI 行数（从本地读取）
    private int aiCols;                                         // AI 列数（从本地读取）
    private boolean aiIsHorizontalLayout;                       // AI 横向排列（从本地读取）
    private static final int MAX_AI_CARD_ITEMS = 100;           // ⭐ 最大卡片数量（和抓拍一样）
    
    // ⭐ 拖动限速：记录拖动开始时的位置
    private volatile int dragStartPosition = 0;
    private static final int MAX_DRAG_FRAMES = 5;  // 单次拖动最大步进帧数

    // ⭐ 实时流文件夹和回放文件夹
    private String realtimeDirectory = "runtime/captures/ssl";  // 实时流目录
    private String playbackDirectory = "runtime/captures/ssl";  // 回放目录
    //D:\javafx\Acard\runtime\captures\scaleslow

    private String scaleplaybackDirectory =  "runtime/captures/scaleslow/";
    //private String scaleplaybackDirectory = "runtime/captures/scaleslow";  // 回放目录
    private int maxFrames;

    private PrintWriter recordLogger;

    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }

    /**
     * ⭐ 延迟执行方法（用于UI操作）
     * @param delaySeconds 延迟时间（秒）
     * @param action 延迟后执行的操作
     */
    private void delayExecute(double delaySeconds, Runnable action) {
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
            javafx.util.Duration.seconds(delaySeconds)
        );
        delay.setOnFinished(event -> action.run());
        delay.play();
    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {


        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/ui/kzq_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            recordLogger = new PrintWriter(new FileWriter(logFile, true));

            // 获取最大帧数
            maxFrames = SlowmoStore.getInstance().getSlowmoFrames();

            // ⭐ 创建播放器（使用回放目录）
            player = new DiskJpegPlayer(playbackDirectory, "s_%09d.jpeg", 1, 1, maxFrames);

            // 设置帧回调
            player.setFrameCallback(this::onFrameUpdate);
            player.setPlayStateCallback(this::onStateCallBack);




            // ⭐ 绑定 ImageView 到 mainContentPane 的大小（拉伸铺满，不裁剪）
            imageView.setPreserveRatio(false);  // ✅ 拉伸铺满整个容器
            imageView.fitWidthProperty().bind(mainContentPane.widthProperty());
            imageView.fitHeightProperty().bind(mainContentPane.heightProperty());
            
            // ✅ 确保 videoContainer 也填满 mainContentPane
            videoContainer.prefWidthProperty().bind(mainContentPane.widthProperty());
            videoContainer.prefHeightProperty().bind(mainContentPane.heightProperty());

            // 初始化速度选择 MenuButton
            String[] speeds = {"0.1x", "0.2x", "0.3x", "0.4x", "0.5x", "0.6x", "0.7x", "0.8x", "0.9x", "1x"};
            currentSpeedIndex = speeds.length - 1;  // 默认1x
            for (String speed : speeds) {
                MenuItem item = new MenuItem(speed);
                item.setOnAction(e -> {
                    speedMenuButton.setText(speed);
                    for (int i = 0; i < speeds.length; i++) {
                        if (speeds[i].equals(speed)) {
                            currentSpeedIndex = i;
                            break;
                        }
                    }
                    onSpeedChanged();
                    // ✅ 保存慢放倍数到本地
                    SlowmoStore.getInstance().setSlowmoSpeed(speed);
                });
                speedMenuButton.getItems().add(item);
            }
            
            // ✅ 从本地存储读取上次保存的速度
            String savedSpeed = SlowmoStore.getInstance().getSlowmoSpeed();
            // 转换格式：SlowmoStore 存的是 "1.0x"，这里需要 "1x" 格式
            String displaySpeed = savedSpeed.replace(".0x", "x");
            // 检查是否在有效范围内
            boolean found = false;
            for (int i = 0; i < speeds.length; i++) {
                if (speeds[i].equals(displaySpeed)) {
                    currentSpeedIndex = i;
                    found = true;
                    break;
                }
            }
            speedMenuButton.setText(found ? displaySpeed : "1x");
            System.out.println("✅ [Element2_2] 从本地读取慢放倍数: " + savedSpeed + " -> " + speedMenuButton.getText());
            
            // ⚡ 添加滚轮控制速度
            speedMenuButton.setOnScroll(e -> {
                int newIndex;
                if (e.getDeltaY() > 0) {
                    // 向上滚 = 加速（索引+1）
                    newIndex = Math.min(currentSpeedIndex + 1, speeds.length - 1);
                } else {
                    // 向下滚 = 减速（索引-1）
                    newIndex = Math.max(currentSpeedIndex - 1, 0);
                }
                
                if (newIndex != currentSpeedIndex) {
                    currentSpeedIndex = newIndex;
                    speedMenuButton.setText(speeds[newIndex]);
                    onSpeedChanged();
                    // ✅ 保存慢放倍数到本地
                    SlowmoStore.getInstance().setSlowmoSpeed(speeds[newIndex]);
                }
                e.consume();
            });

            // 播放/暂停按钮
            playPauseButton.setOnAction(e -> togglePlayPause());

            // 实时流按钮
            //realtimeButton.setOnAction(e -> switchToRealtimeMode());

            // ========== 进度条设置 ==========
            // 进度条范围：0 到 已下载帧数（动态更新）
            progressSlider.setMin(0);
            progressSlider.setMax(1);  // 初始值，后续动态更新
            progressSlider.setValue(0);
            
            // ⭐ 开始拖动：进入 DRAGGING 模式，记录起始位置
            progressSlider.setOnMousePressed(e -> {
                if (copiedFrameCount > 0) {  // 有帧才能拖动
                    enterDraggingMode();
                    // 记录拖动开始时的位置
                    dragStartPosition = currentPosition;
                }
            });
            
            // ⭐ 拖动结束：根据位置决定进入哪个模式
            progressSlider.setOnMouseReleased(e -> {
                if (currentUIMode == UIMode.DRAGGING) {
                    exitDraggingMode();
                }
            });
            
            // ⭐ 拖动中：实时预览对应帧（限制最大步进5帧）
            progressSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (currentUIMode == UIMode.DRAGGING && copiedFrameCount > 0) {
                    int targetPos = (int)(newVal.doubleValue() * copiedFrameCount);
                    targetPos = Math.max(0, Math.min(copiedFrameCount - 1, targetPos));
                    
                    // ⭐ 限制拖动速度：每次最多移动5帧
                    int delta = targetPos - dragStartPosition;
                    if (Math.abs(delta) > MAX_DRAG_FRAMES) {
                        // 限制到最大步进
                        targetPos = dragStartPosition + (delta > 0 ? MAX_DRAG_FRAMES : -MAX_DRAG_FRAMES);
                        targetPos = Math.max(0, Math.min(copiedFrameCount - 1, targetPos));
                        
                        // 更新起始位置（允许继续拖动）
                        dragStartPosition = targetPos;
                        
                        // 更新滑块位置（避免UI跳动）
                        double newSliderVal = (double) targetPos / copiedFrameCount;
                        progressSlider.setValue(newSliderVal);
                    } else {
                        // 正常步进，更新起始位置
                        dragStartPosition = targetPos;
                    }
                    
                    previewFrameAtPosition(targetPos);
                }
            });

            // ⭐ 默认为实时流模式
            player.switchToRealtimeMode();
            updateModeLabel();
            registerUIUpdateEvents();
            ensureDirectoriesExist();

            // ⭐ 添加滚轮事件监听（监听根容器或图像显示区域）
            // 假设你的根节点是 rootPane 或 imageView
            videoContainer.setOnScroll(event -> {
                handleScroll(event);
                event.consume();  // 阻止事件继续传播
            });
            
            // ⭐ 监听鼠标进入/离开慢放区域
            videoContainer.setOnMouseEntered(e -> {
                mouseInSlowmoArea = true;
                System.out.println("🖱️ 鼠标进入慢放区域");
            });
            videoContainer.setOnMouseExited(e -> {
                mouseInSlowmoArea = false;
                System.out.println("🖱️ 鼠标离开慢放区域");
            });
            
            // ⭐ 添加键盘左右键监听（Scene级别，但只有鼠标在区域内才处理）
            videoContainer.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    newScene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
                    logRecord("✅ 键盘监听已绑定到 Scene");
                }
            });
            
            // ========== 初始化 AI 视图 ==========
            initializeAiView();
            
        } catch (Exception e) {
            logRecord("日志初始化失败: " + e.getMessage());
        }
        logRecord("✅ Element2_2JpegController 初始化完成");
    }
    
    /**
     * ⚡ 初始化 AI 视图
     */
    private void initializeAiView() {
        // ⭐ 从本地读取 AI 配置
        aiRows = GridStore.getInstance().getAiRows();
        aiCols = GridStore.getInstance().getAiCols();
        aiIsHorizontalLayout = GridStore.getInstance().isAiHorizontalLayout();
        
        // 配置 AI 网格
        if (aiGrid != null) {
            aiGrid.setStyle("-fx-background-color: #1F1F1F; -fx-padding: 2;");
            aiGrid.setSnapToPixel(false);
            aiGrid.setHgap(2);
            aiGrid.setVgap(2);
        }
        
        // 配置 AI ScrollPane
        if (aiScroll != null) {
            aiScroll.setFitToWidth(true);
            aiScroll.setFitToHeight(true);
            aiScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            aiScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            aiScroll.setStyle("-fx-background-color: #1F1F1F; -fx-background: #1F1F1F; -fx-border-color: transparent;");
            
            // 设置 viewport 背景色
            Platform.runLater(() -> {
                javafx.scene.Node viewport = aiScroll.lookup(".viewport");
                if (viewport != null) {
                    viewport.setStyle("-fx-background-color: #1F1F1F;");
                }
            });
            
            // 监听尺寸变化
            aiScroll.widthProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() > 0 && aiLayoutManager != null) {
                    aiLayoutManager.refreshItemSizes();
                }
            });
            aiScroll.heightProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() > 0 && aiScroll.getWidth() > 0 && aiLayoutManager != null) {
                    aiLayoutManager.refreshItemSizes();
                }
            });
        }
        
        // 初始化 AI 布局管理器
        if (aiGrid != null && aiScroll != null) {
            aiLayoutManager = new AiGridLayoutManager(aiGrid, aiScroll, aiCardItems);
            aiLayoutManager.setGridSize(aiRows, aiCols);
            aiLayoutManager.setLayoutDirection(aiIsHorizontalLayout);
        }
        
        // 初始化行数选择菜单 + 滚轮操作
        if (aiRowMenuButton != null) {
            aiRowMenuButton.setText(String.valueOf(aiRows));
            for (int i = 1; i <= 10; i++) {
                MenuItem item = new MenuItem(String.valueOf(i));
                int finalI = i;
                item.setOnAction(e -> setAiRows(finalI));
                aiRowMenuButton.getItems().add(item);
            }
            // ⭐ 滚轮调整行数
            aiRowMenuButton.setOnScroll(e -> {
                int newRows = aiRows + (e.getDeltaY() > 0 ? 1 : -1);
                newRows = Math.max(1, Math.min(10, newRows));
                if (newRows != aiRows) {
                    setAiRows(newRows);
                }
                e.consume();
            });
        }
        
        // 初始化列数选择菜单 + 滚轮操作
        if (aiColMenuButton != null) {
            aiColMenuButton.setText(String.valueOf(aiCols));
            for (int i = 1; i <= 10; i++) {
                MenuItem item = new MenuItem(String.valueOf(i));
                int finalI = i;
                item.setOnAction(e -> setAiCols(finalI));
                aiColMenuButton.getItems().add(item);
            }
            // ⭐ 滚轮调整列数
            aiColMenuButton.setOnScroll(e -> {
                int newCols = aiCols + (e.getDeltaY() > 0 ? 1 : -1);
                newCols = Math.max(1, Math.min(10, newCols));
                if (newCols != aiCols) {
                    setAiCols(newCols);
                }
                e.consume();
            });
        }
        
        // 初始化横向/纵向切换按钮
        if (aiLayoutToggleButton != null) {
            aiLayoutToggleButton.setText(aiIsHorizontalLayout ? "横向" : "纵向");
            aiLayoutToggleButton.setOnAction(e -> toggleAiLayoutDirection());
        }
        
        // 初始化清空按钮
        if (aiClearButton != null) {
            aiClearButton.setOnAction(e -> clearAllAiCardData());
        }
        
        // 初始化测试按钮
        if (aiTestButton != null) {
            aiTestButton.setOnAction(e -> addRandomAiCard());
        }
        
        // 初始化 AI 布局（显示占位符）
        refreshAiLayout();
        
        logRecord("✅ AI 视图初始化完成 - " + aiRows + "x" + aiCols + (aiIsHorizontalLayout ? " 横向" : " 纵向"));
    }
    
    /**
     * ⚡ 清空所有 AI 卡片数据
     */
    private void clearAllAiCardData() {
        Platform.runLater(() -> {
            // 清空所有卡片
            for (AiCardItem item : aiCardItems) {
                item.clear();
            }
            aiCardItems.clear();
            
            // 刷新布局（显示占位符）
            if (aiLayoutManager != null) {
                aiLayoutManager.refreshLayout();
            }
            
            logRecord("🧹 已清空所有 AI 卡片数据");
        });
    }
    
    /**
     * ⚡ 随机添加一张卡片（和抓拍逻辑一样）
     */
    private void addRandomAiCard() {
        java.util.Random random = new java.util.Random();
        
        // 随机点数
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        String rank = ranks[random.nextInt(ranks.length)];
        
        // 随机花色
        AiCardItem.Suit[] suits = AiCardItem.Suit.values();
        AiCardItem.Suit suit = suits[random.nextInt(suits.length)];
        
        // 添加新卡片
        addNewAiCard(rank, suit);
    }
    
    /**
     * ⚡ 添加新的 AI 卡片（和抓拍逻辑一样）
     * - 自动管理 100 个上限
     * - 增量添加，不触发完整布局
     */
    public void addNewAiCard(String rank, AiCardItem.Suit suit) {
        Platform.runLater(() -> {
            // 防止内存爆炸，超过100个删除最旧的
            if (aiCardItems.size() >= MAX_AI_CARD_ITEMS) {
                AiCardItem oldest = aiCardItems.remove(0);
                oldest.clear();
                if (aiLayoutManager != null) {
                    aiLayoutManager.removeItem(oldest, 0);
                }
                logRecord("⚠️ AI卡片达到上限(100)，删除最旧项");
            }
            
            // 创建新卡片
            AiCardItem newItem = new AiCardItem();
            newItem.setCard(rank, suit);
            
            // 添加到列表
            aiCardItems.add(newItem);
            int idx = aiCardItems.size() - 1;
            
            // 使用布局管理器添加
            if (aiLayoutManager != null) {
                aiLayoutManager.addItem(newItem, idx);
            }
            
            logRecord("🎴 新增AI卡片[" + idx + "]: " + suit.getName() + rank + ", 当前共 " + aiCardItems.size() + " 个");
        });
    }
    
    /**
     * ⚡ 刷新 AI 网格布局（不清空数据，和抓拍一样）
     */
    private void refreshAiLayout() {
        Platform.runLater(() -> {
            if (aiLayoutManager != null) {
                aiLayoutManager.setGridSize(aiRows, aiCols);
            }
            logRecord("✅ AI 刷新布局 " + aiRows + "x" + aiCols + ", 当前 " + aiCardItems.size() + " 个卡片");
        });
    }
    
    /**
     * ⚡ 刷新 AI 布局尺寸（供外部调用，窗口切换后使用）
     * 调用完整的 refreshLayout 而非只刷新尺寸，确保布局不会重叠
     */
    public void refreshAiLayoutSize() {
        if (aiLayoutManager != null && isAiViewShowing) {
            Platform.runLater(() -> {
                // ⚡ 先清除所有 items 在 grid 中的位置，再重新布局
                aiLayoutManager.refreshLayout();
            });
        }
    }
    
    /**
     * ⚡ 切换 AI 视图显示（供外部调用）
     */
    public void toggleAiView() {
        isAiViewShowing = !isAiViewShowing;
        
        Platform.runLater(() -> {
            if (isAiViewShowing) {
                // 显示 AI 视图，隐藏慢放视图
                videoContainer.setVisible(false);
                aiScroll.setVisible(true);
                
                // 切换底部控制面板
                slowmoControlBar.setVisible(false);
                aiControlBar.setVisible(true);
                
                // ⚡ 多次延迟刷新 AI 布局（等待 aiScroll 尺寸更新）
                if (aiLayoutManager != null) {
                    aiLayoutManager.refreshItemSizes();
                    // 延迟刷新，确保布局完成
                    Platform.runLater(() -> {
                        aiLayoutManager.refreshItemSizes();
                        Platform.runLater(() -> aiLayoutManager.refreshItemSizes());
                    });
                }
                
                logRecord("🔄 切换到 AI 视图");
            } else {
                // 显示慢放视图，隐藏 AI 视图
                videoContainer.setVisible(true);
                aiScroll.setVisible(false);
                
                // 切换底部控制面板
                slowmoControlBar.setVisible(true);
                aiControlBar.setVisible(false);
                
                logRecord("🔄 切换到慢放视图");
            }
        });
    }
    
    /**
     * ⚡ 设置 AI 行数（不清空数据，和抓拍一样）
     */
    private void setAiRows(int rows) {
        if (rows < 1 || rows > 10 || rows == aiRows) return;
        
        aiRows = rows;
        aiRowMenuButton.setText(String.valueOf(rows));
        
        // ⭐ 保存到本地
        GridStore.getInstance().setAiRows(rows);
        
        // 只刷新布局，不清空数据
        refreshAiLayout();
        
        logRecord("📐 AI 设置行数: " + aiRows);
    }
    
    /**
     * ⚡ 设置 AI 列数（不清空数据，和抓拍一样）
     */
    private void setAiCols(int cols) {
        if (cols < 1 || cols > 10 || cols == aiCols) return;
        
        aiCols = cols;
        aiColMenuButton.setText(String.valueOf(cols));
        
        // ⭐ 保存到本地
        GridStore.getInstance().setAiCols(cols);
        
        // 只刷新布局，不清空数据
        refreshAiLayout();
        
        logRecord("📐 AI 设置列数: " + aiCols);
    }
    
    /**
     * ⚡ 切换 AI 横向/纵向排列
     */
    private void toggleAiLayoutDirection() {
        aiIsHorizontalLayout = !aiIsHorizontalLayout;
        aiLayoutToggleButton.setText(aiIsHorizontalLayout ? "横向" : "纵向");
        
        // ⭐ 保存到本地
        GridStore.getInstance().setAiHorizontalLayout(aiIsHorizontalLayout);
        
        if (aiLayoutManager != null) {
            aiLayoutManager.setLayoutDirection(aiIsHorizontalLayout);
        }
        
        logRecord("🔄 AI 切换排列方向: " + (aiIsHorizontalLayout ? "横向" : "纵向"));
    }
    
    /**
     * ⚡ 更新指定索引的 AI 卡片数据
     * @param index 卡片索引
     * @param rank 点数 (2-10, J, Q, K, A)
     * @param suit 花色
     */
    public void updateAiCardData(int index, String rank, AiCardItem.Suit suit) {
        Platform.runLater(() -> {
            if (index >= 0 && index < aiCardItems.size()) {
                aiCardItems.get(index).setCard(rank, suit);
            } else {
                logRecord("⚠️ 无效的AI卡片索引: " + index);
            }
        });
    }
    
    /**
     * ⚡ 清空所有 AI 卡片（和抓拍一样）
     */
    public void clearAllAiCards() {
        Platform.runLater(() -> {
            for (AiCardItem item : aiCardItems) {
                item.clear();
            }
            aiCardItems.clear();
            if (aiGrid != null) {
                aiGrid.getChildren().clear();
            }
            // 重新初始化占位符
            if (aiLayoutManager != null) {
                aiLayoutManager.setGridSize(aiRows, aiCols);
            }
            logRecord("✅ 已清空所有AI卡片");
        });
    }
    
    /**
     * ⚡ 删除指定的 AI 卡片（和抓拍一样）
     */
    public void removeAiCard(AiCardItem item) {
        Platform.runLater(() -> {
            int index = aiCardItems.indexOf(item);
            if (index >= 0) {
                item.clear();
                aiCardItems.remove(index);
                if (aiLayoutManager != null) {
                    aiLayoutManager.removeItem(item, index);
                }
                logRecord("✅ 删除AI卡片[" + index + "], 剩余 " + aiCardItems.size() + " 个");
            }
        });
    }
    
    /**
     * ⚡ 删除最后一个 AI 卡片
     */
    public boolean removeLastAiCard() {
        if (aiCardItems.isEmpty()) {
            return false;
        }
        removeAiCard(aiCardItems.get(aiCardItems.size() - 1));
        return true;
    }
    
    /**
     * ⚡ 添加 AI 测试数据（批量添加多张卡片）
     */
    public void addAiTestData() {
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        AiCardItem.Suit[] suits = AiCardItem.Suit.values();
        
        int totalItems = aiRows * aiCols;
        for (int i = 0; i < totalItems; i++) {
            String rank = ranks[i % ranks.length];
            AiCardItem.Suit suit = suits[i % suits.length];
            addNewAiCard(rank, suit);
        }
    }
    
    /**
     * ⚡ 获取当前 AI 卡片数量
     */
    public int getAiCardCount() {
        return aiCardItems.size();
    }
    
    /**
     * ⚡ 获取当前是否显示 AI 视图
     */
    public boolean isAiViewShowing() {
        return isAiViewShowing;
    }
    
    /**
     * ⭐ 处理键盘事件（左右键一帧步进）
     * 只有鼠标在慢放区域内才处理
     */
    private void handleKeyPressed(KeyEvent event) {
        // ⭐ 只有鼠标在慢放区域内才处理键盘事件
        if (!mouseInSlowmoArea) {
            return;
        }
        
        KeyCode code = event.getCode();
        
        // 支持主键盘和小键盘的左右键
        if (code == KeyCode.LEFT || code == KeyCode.KP_LEFT) {
            // 左键 = 上一帧
            System.out.println("⬅️ 键盘左键：上一帧");
            stepFrame(-1);  // 使用新的步进方法
            event.consume();
        } else if (code == KeyCode.RIGHT || code == KeyCode.KP_RIGHT) {
            // 右键 = 下一帧
            System.out.println("➡️ 键盘右键：下一帧");
            stepFrame(1);   // 使用新的步进方法
            event.consume();
        }
    }
    
    /**
     * ⭐ 步进帧（基于当前位置）
     * @param delta 步进量：-1=上一帧，+1=下一帧
     */
    private void stepFrame(int delta) {
        if (copiedFrameCount <= 0) {
            return;
        }
        
        // 计算新位置
        int newPos = currentPosition + delta;
        newPos = Math.max(0, Math.min(copiedFrameCount - 1, newPos));
        
        if (newPos != currentPosition) {
            currentPosition = newPos;
            
            // 预览该帧
            previewFrameAtPosition(newPos);
            
            // 更新滑块位置
            double sliderVal = (double) newPos / Math.max(1, copiedFrameCount);
            int finalNewPos = newPos;
            Platform.runLater(() -> {
                progressSlider.setValue(sliderVal);
                positionLabel.setText(String.valueOf(finalNewPos + 1));
            });
            
            System.out.println("🎬 步进到帧: " + (newPos + 1) + "/" + copiedFrameCount);
        }
    }
    /**
     * 处理滚轮事件（一帧步进）
     */
    private void handleScroll(ScrollEvent event) {
        double deltaY = event.getDeltaY();

        if (deltaY > 0) {
            // ⭐ 向上滚动（远离用户）= 上一帧
            stepFrame(-1);
        } else if (deltaY < 0) {
            // ⭐ 向下滚动（靠近用户）= 下一帧
            stepFrame(1);
        }
    }

    /**
     * 上一帧
     */
    private void previousFrame() {
        // 你的逻辑
        player.previousFrame();
    }

    /**
     * 下一帧
     */
    private void nextFrame() {
        // 你的逻辑
        player.nextFrame();
    }


    private void ensureDirectoriesExist() {
        String[] directories = {
                "runtime/captures/scaleslow"
        };

        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) {
                boolean created = directory.mkdirs();
                if (created) {
                    System.out.println("✅ 创建目录: " + dir);
                }
            }
        }
    }

    private final UIUpdateEventManager eventManager = UIUpdateEventManager.getInstance();
    private final String listenerId = "slowc_" + System.currentTimeMillis();
    private final String listenerId2 = "fileslowc_" + System.currentTimeMillis();

    private final String listenerId3 = "slowclean"+System.currentTimeMillis();

    private final String  listenerId4 = "jieSuan"+System.currentTimeMillis();
    private volatile boolean eventListenersRegistered = false;


    /**
     * 录制开始事件：重置状态，进入实时流模式
     */
    public void handleUIUpdateEvent(UIUpdateEvent event) {
        // 重置状态
        copiedFrameCount = 0;
        currentPosition = 0;
        currentUIMode = UIMode.REALTIME;
        
        player.stop();
        player.switchToRealtimeMode();
        player.initData(0, 0, SlowmoStore.getInstance().getSlowmoFrames());
        
        // 清理图像缓存
        reusableImage = null;
        frameUpdateCount = 0;
        System.gc();
        logRecord("🧹 录制开始，重置为 REALTIME 模式");
    }

    /**
     * 收到新帧事件：推送帧，保持实时流模式
     */
    public void handleUIUpdateEvent2(UIUpdateEvent event) {
        this.diu = 0;
        
        UIUpdateEvent.FsFilePathData filePathData = (UIUpdateEvent.FsFilePathData) event.getData();
        int startIndex = filePathData.frameIndex;
        
        if (player.getStartFrame() == 0) {
            player.setStartFrame(startIndex);
            player.setMaxFrame(SlowmoStore.getInstance().getSlowmoFrames());
            player.setEndFrame(startIndex + SlowmoStore.getInstance().getSlowmoFrames() - 1);
            
            // 进入实时流模式
            currentUIMode = UIMode.REALTIME;
            player.switchToRealtimeMode();
            player.setRealPlaying(true);
            updateState();
            
            CaptureData captureData = new CaptureData();
            captureData.setStartIndex(startIndex);
            captureData.setEndIndex(startIndex + player.getMaxFrame());
            CaptureDataManager.slowPly = System.currentTimeMillis() + "slowPlay";
            CaptureDataManager.getInstance().put(CaptureDataManager.slowPly, captureData);
        }

        pushNewFrame(filePathData.filepath, startIndex);
    }

    public void handleUIUpdateEvent3(UIUpdateEvent event){

        clearAll();
    }

    public void registerUIUpdateEvents() {

        try {
            // 注册强制刷新事件
            eventManager.registerListener(UIUpdateEvent.EventType.RecordingStartedEvent,
                    this::handleUIUpdateEvent, listenerId);
            eventManager.registerListener(UIUpdateEvent.EventType.SendFilePathEvent,
                    this::handleUIUpdateEvent2, listenerId2);
            eventManager.registerListener(UIUpdateEvent.EventType.SlowCleanEvent,
                    this::handleUIUpdateEvent3, listenerId3);
            eventManager.registerListener(UIUpdateEvent.EventType.JiesuanCountEvent,
                    this::handleUIUpdateEvent4, listenerId4);

        } catch (Exception e) {
            System.err.println("❌ 注册UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 注销UI更新事件监听器
     */
    public void unregisterUIUpdateEvents() {

        try {
            // 注销所有该监听器ID的事件
            eventManager.unregisterAllListeners(listenerId);
            eventManager.unregisterAllListeners(listenerId2);
            eventManager.unregisterAllListeners(listenerId3);
            eventManager.unregisterAllListeners(listenerId4);
            eventListenersRegistered = false;

        } catch (Exception e) {
            System.err.println("❌ 注销UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public void handleUIUpdateEvent4(UIUpdateEvent event){

        jiesuanCount();
    }

    /**
     * 结算事件：停止实时流，进入回放模式待命
     */
    public void jiesuanCount() {
        if (FileToos.isCallBack == false) {
            logRecord("📊 结算：切换到 PLAYBACK 模式");
            currentUIMode = UIMode.PLAYBACK;
            
            player.setRealPlaying(false);
            player.switchToPlaybackMode();
            player.stop();
            player.setCopyCount(copiedFrameCount);
            updateState();
        }
    }


    /**
     * 播放/暂停切换
     */
    /**
     * 播放/暂停切换（根据当前 UI 模式）
     */
    private void togglePlayPause() {
        if (!CaptureDataManager.isActive()) {
            logRecord("togglePlayPause: 未激活");
            return;
        }
        
        logRecord("togglePlayPause: 当前模式=" + currentUIMode);
        
        switch (currentUIMode) {
            case REALTIME:
                // 实时流模式：切换实时播放状态
                if (player.isRealPlaying()) {
                    player.setRealPlaying(false);
                    playPauseButton.setText("播放");
                    playPauseButton.setStyle(PLAY_STYLE);
                } else {
                    player.setRealPlaying(true);
                    playPauseButton.setText("暂停");
                    playPauseButton.setStyle(PAUSE_STYLE);
                }
                break;
                
            case PLAYBACK:
                // 回放模式：切换播放/暂停
                if (player.isPlaying()) {
                    player.pause();
                    playPauseButton.setText("播放");
                    playPauseButton.setStyle(PLAY_STYLE);
                } else {
                    player.play();
                    playPauseButton.setText("暂停");
                    playPauseButton.setStyle(PAUSE_STYLE);
                }
                break;
                
            case DRAGGING:
                // 拖动模式：不响应播放/暂停
                break;
        }
    }

    /**
     * 更新播放按钮状态（根据当前 UI 模式）
     */
    public void updateState() {
        Platform.runLater(() -> {
            switch (currentUIMode) {
                case REALTIME:
                    if (player.isRealPlaying()) {
                        playPauseButton.setStyle(PAUSE_STYLE);
                        playPauseButton.setText("暂停");
                    } else {
                        playPauseButton.setStyle(PLAY_STYLE);
                        playPauseButton.setText("播放");
                    }
                    break;
                    
                case PLAYBACK:
                    if (player.isPlaying()) {
                        playPauseButton.setStyle(PAUSE_STYLE);
                        playPauseButton.setText("暂停");
                    } else {
                        playPauseButton.setStyle(PLAY_STYLE);
                        playPauseButton.setText("播放");
                    }
                    break;
                    
                case DRAGGING:
                    playPauseButton.setStyle(PLAY_STYLE);
                    playPauseButton.setText("播放");
                    break;
            }
        });
    }

    /**
     * 速度改变
     */
    private void onSpeedChanged() {
        if(CaptureDataManager.isActive()) {
            String speedStr = speedMenuButton.getText();
            double speed = Double.parseDouble(speedStr.replace("x", ""));
            player.setPlaybackSpeed(speed);
            //speedLabel.setText(String.format("%.2fx", speed));
            updateModeLabel();
            updateState();
        }
    }

    /**
     * 切换到实时流模式
     */
    private void switchToRealtimeMode() {
        if (copiedFrameCount >= maxFrames) {
            // 超过限制，不允许切换
            Alert alert = new Alert(Alert.AlertType.WARNING);
            // ⭐ 设置父窗口，防止全屏时层级错乱
            if (videoContainer != null && videoContainer.getScene() != null && videoContainer.getScene().getWindow() != null) {
                javafx.stage.Stage ownerStage = (javafx.stage.Stage) videoContainer.getScene().getWindow();
                alert.initOwner(ownerStage);
                // ⭐ 全屏保护
                alert.setOnShowing(e -> ownerStage.toFront());
                alert.setOnHidden(e -> Platform.runLater(() -> { ownerStage.toFront(); ownerStage.requestFocus(); }));
            }
            alert.setTitle("提示");
            alert.setHeaderText("已达到慢放帧数上限");
            alert.setContentText("当前已缓存 " + copiedFrameCount + " 帧，上限为 " + maxFrames + " 帧\n无法切换到实时流模式");
            alert.showAndWait();
        } else {
            // 切换到实时流模式
            player.switchToRealtimeMode();
            updateModeLabel();
        }
    }
    String scalepath="";
    public void pushNewFrame(String sourcePath, int frameIndex) {
        if (player == null) return;

        if (copiedFrameCount >= maxFrames) {
            player.setRealPlaying(false);
            FileToos.isCallBack=false;
            FileToos.FbRecordingStoppedEvent();

            return; // 超过限制，不复制
        }


        // 异步复制文件
        boolean copied= copyFrameAsync(sourcePath, frameIndex);

        //scaleslow
        if (copied) {
           // String targetFilename = String.format("s_%09d.jpeg", frameIndex);

           // String scalepath = GStreamerJpegScaler.getInstance().scaleAndSave(sourcePath,scaleplaybackDirectory+targetFilename);
            // 推送到播放器（实时流模式会立即显示）
            //player.pushRealtimeFrame(sourcePath, frameIndex);
            if(player.isRealPlaying()){
                player.pushRealtimeFrame(scalepath, frameIndex);
                updateModeLabel();
            }

        }
    }


    private void onStateCallBack(){
        updateState();
    }

    /**
     * 帧更新回调（根据当前 UI 模式决定行为）
     * 
     * - REALTIME/PLAYBACK 模式：更新画面、位置、进度条
     * - DRAGGING 模式：只更新已下载帧数，画面由 previewFrameAtPosition 控制
     */
    private void onFrameUpdate(BufferedImage image, int frameIndex, int totalFrames) {
        if (image == null) return;
        
        final int width = image.getWidth();
        final int height = image.getHeight();
        
        Platform.runLater(() -> {
            try {
                // ========== 始终更新：已下载帧数 ==========
                updateDownloadedLabel();
                
                // ========== DRAGGING 模式：不更新画面和位置 ==========
                if (currentUIMode == UIMode.DRAGGING) {
                    image.flush();
                    return;
                }
                
                // ========== REALTIME/PLAYBACK 模式：更新画面 ==========
                // 复用 WritableImage 对象
                if (reusableImage == null || 
                    (int)reusableImage.getWidth() != width || 
                    (int)reusableImage.getHeight() != height) {
                    reusableImage = new javafx.scene.image.WritableImage(width, height);
                }
                
                javafx.scene.image.WritableImage fxImage = 
                    SwingFXUtils.toFXImage(image, reusableImage);
                imageView.setImage(fxImage);
                
                // 更新当前位置
                currentPosition = frameIndex;
                updatePositionLabel(frameIndex);
                
                // 更新进度条（基于已下载帧数）
                updateProgressSlider(frameIndex);
                
                // 每100帧触发一次GC
                frameUpdateCount++;
                if (frameUpdateCount % 100 == 0) {
                    System.gc();
                }
                
            } finally {
                image.flush();
            }
        });
    }

    // ========== 模式切换方法 ==========
    
    /**
     * 进入拖动模式
     */
    private void enterDraggingMode() {
        logRecord("🔄 进入 DRAGGING 模式");
        currentUIMode = UIMode.DRAGGING;
        
        // 暂停播放器（如果在回放模式）
        if (player.isPlaying()) {
            player.pause();
        }
        // 暂停实时流画面更新
        player.setRealPlaying(false);
    }
    
    /**
     * 退出拖动模式，根据滑块位置决定进入哪个模式
     */
    private void exitDraggingMode() {
        if (copiedFrameCount <= 0) {
            // 没有下载帧，回到实时流模式
            enterRealtimeMode();
            return;
        }
        
        double progress = progressSlider.getValue();
        int targetPos = (int)(progress * copiedFrameCount);
        
        // 如果拖到最右边（最新帧位置），回到实时流模式
        if (targetPos >= copiedFrameCount - 3) {
            enterRealtimeMode();
        } else {
            // 否则进入回放模式，从该位置开始
            enterPlaybackMode(targetPos);
        }
    }
    
    /**
     * 进入实时流模式
     */
    private void enterRealtimeMode() {
        logRecord("🔴 进入 REALTIME 模式");
        currentUIMode = UIMode.REALTIME;
        
        player.switchToRealtimeMode();
        player.setRealPlaying(true);
        
        // 更新 UI
        Platform.runLater(() -> {
            playPauseButton.setText("暂停");
            playPauseButton.setStyle(PAUSE_STYLE);
            // ✅ 开启慢放时不重置速度，保留用户上次选择的倍数
            // speedMenuButton.setText("1x");  // 已注释：不再重置速度
        });
    }
    
    /**
     * 进入回放模式
     */
    private void enterPlaybackMode(int startPosition) {
        logRecord("🔵 进入 PLAYBACK 模式，起始位置: " + startPosition);
        currentUIMode = UIMode.PLAYBACK;
        
        // 设置播放器到指定位置
        int startFrame = player.getStartFrame();
        player.switchToPlaybackMode();
        player.seekToFrame(startFrame + startPosition);
        
        // 更新 UI
        Platform.runLater(() -> {
            playPauseButton.setText("播放");
            playPauseButton.setStyle(PLAY_STYLE);
        });
    }
    
    /**
     * 预览指定位置的帧（拖动模式专用）
     */
    private void previewFrameAtPosition(int position) {
        if (player == null || copiedFrameCount <= 0) return;
        
        currentPosition = position;
        int startFrame = player.getStartFrame();
        int targetFrame = startFrame + position;
        
        // 更新位置标签
        Platform.runLater(() -> {
            positionLabel.setText(String.valueOf(position + 1));
        });
        
        // 异步加载并显示预览帧
        copyExecutor.submit(() -> {
            try {
                BufferedImage frame = player.loadFrame2(targetFrame);
                if (frame != null) {
                    Platform.runLater(() -> {
                        try {
                            javafx.scene.image.WritableImage fxImage = 
                                SwingFXUtils.toFXImage(frame, reusableImage);
                            imageView.setImage(fxImage);
                        } finally {
                            frame.flush();
                        }
                    });
                }
            } catch (Exception e) {
                logRecord("预览帧加载失败: " + e.getMessage());
            }
        });
    }
    
    // ========== UI 更新方法 ==========
    
    /**
     * 更新播放位置 Label
     */
    private void updatePositionLabel(int position) {
        positionLabel.setText(String.valueOf(position + 1));
    }
    
    /**
     * 更新已下载帧数 Label（始终更新，不受模式影响）
     */
    private void updateDownloadedLabel() {
        downloadedLabel.setText(String.valueOf(copiedFrameCount));
    }
    
    /**
     * 更新进度条位置（根据当前位置和已下载帧数）
     */
    private void updateProgressSlider(int position) {
        if (copiedFrameCount > 0) {
            double progress = (double) position / copiedFrameCount;
            progressSlider.setValue(Math.min(1.0, progress));
        }
    }

    /**
     * 更新模式显示
     */
    private void updateModeLabel() {
        /*if (player != null && modeLabel != null) {
            Platform.runLater(() -> {
                if (player.isRealtimeMode()) {
                    // 🔴 实时流模式
                    modeLabel.setText("🔴 实时流");
                    modeLabel.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold; -fx-font-size: 13px;");

                    // ⭐ 更新速度选择框为 1x
                    speedMenuButton.setText("1x");
                    speedLabel.setText("1.0x");

                } else {
                    // 🔵 回放模式
                    modeLabel.setText("🔵 回放");
                    modeLabel.setStyle("-fx-text-fill: #4a90e2; -fx-font-weight: bold; -fx-font-size: 13px;");

                    // 同步播放状态
                    if (player.isPlaying()) {
                        playPauseButton.setText("暂停");
                    } else {
                        playPauseButton.setText("播放");
                    }
                }
            });
        }*/

        if (player != null ) {
            Platform.runLater(() -> {
                if (player.isRealtimeMode()) {
                    // 🔴 实时流模式
                    // ✅ 不再重置速度，保留用户选择的倍数
                    // speedMenuButton.setText("1x");  // 已注释：不再重置速度
                    //speedLabel.setText("1.0x");

                } else {
                    // 🔵 回放模式
                }
            });
        }
    }

    /**
     * ⭐ 推送新的 JPEG 帧到播放器（从 Element2_3Controller 调用）
     *
     * @param sourcePath 源文件路径（runtime/captures/ssl/s_xxxxx.jpeg）
     * @param frameIndex 帧索引
     */




    /**
     * ⭐ 异步复制 JPEG 文件到回放目录
     */
    private boolean copyFrameAsync(String sourcePath, int frameIndex) {
        if (copiedFrameCount >= maxFrames) {
            return false;
        }

        copyExecutor.submit(() -> {
            try {
                File sourceFile = new File(sourcePath);
                if (!sourceFile.exists()) {
                    System.err.println("❌ 源文件不存在: " + sourcePath);
                    return;
                }

                // 目标文件路径
                String targetFilename = String.format("s_%09d.jpeg", frameIndex);
                File targetFile = new File(playbackDirectory, targetFilename);

                // 确保目录存在
                File targetDir = targetFile.getParentFile();
                if (targetDir != null && !targetDir.exists()) {
                    targetDir.mkdirs();
                }

                // 复制文件
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                if(diu%diuJixing==0) {
                   scalepath =sourcePath;
                    //scalepath = GStreamerJpegScaler.getInstance().scaleAndSave(sourcePath, scaleplaybackDirectory + targetFilename);
                    //scalepath = GStreamerJpegScalerV2.getInstance().scaleAndSave(sourcePath, scaleplaybackDirectory + targetFilename);
                }
                diu++;
                copiedFrameCount++;
                player.setCopyCount(copiedFrameCount);
                
                // ⭐ 始终更新已下载帧数（不受模式影响）
                Platform.runLater(() -> updateDownloadedLabel());
            } catch (IOException e) {
                //System.err.println("❌ 复制文件失败: " + e.getMessage());
            }
        });

        return true;
    }

    private int diu =0;

    private int diuJixing = FileToos.getDiuFps();



    /**
     * ⭐ 重置复制计数器（录制新会话时调用）
     */
    public void resetCopyCount() {
        copiedFrameCount = 0;
        System.out.println("🔄 复制计数器已重置");
    }

    /**
     * ⭐ 停止播放（停子功能）
     * 停止当前播放，但保留已缓存的帧
     */
    public void stopPlayback() {
        if (player == null) {
            logRecord("⚠️ 停止失败：播放器未初始化");
            return;
        }
        
        try {
            logRecord("⏹️ 停止播放...");
            
            // 停止播放器
            if (player.isPlaying() || player.isRealPlaying()) {
                player.pause();
                player.setRealPlaying(false);
            }
            
            // 更新UI状态
            Platform.runLater(() -> {
                playPauseButton.setStyle(PLAY_STYLE);
                playPauseButton.setText("播放");
                logRecord("✅ 播放已停止");
            });
            
        } catch (Exception e) {
            logRecord("❌ 停止播放失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ⭐ 清空所有内容（清空功能）
     * 回到最初状态：清空缓存、重置进度、切换到实时流模式
     */
    public void clearAll() {
        if (player == null) {
            logRecord("⚠️ 清空失败：播放器未初始化");
            return;
        }
        
        try {
            logRecord("🧹 开始清空所有内容...");
            
            // 1. 重置 UI 模式
            currentUIMode = UIMode.REALTIME;
            currentPosition = 0;
            
            // 2. 停止播放

            player.stop();
            player.setRealPlaying(false);
            
            // 3. 切换到实时流模式
            player.switchToRealtimeMode();
            
            // 4. 重置复制计数器
            copiedFrameCount = 0;
            FileToos.slowIndex = 0;
            
            // 5. 重置 UI
            Platform.runLater(() -> {
                progressSlider.setValue(0);
                positionLabel.setText("0");
                downloadedLabel.setText("0");
                
                playPauseButton.setStyle(PLAY_STYLE);
                playPauseButton.setText("播放");
                
                // ✅ 清空时不重置慢放倍数，保留用户上次选择的倍数
                // speedMenuButton.setText("1x");  // 已注释：不再重置速度
                imageView.setImage(null);
                
                if (reusableImage != null) {
                    reusableImage = null;
                }
                frameUpdateCount = 0;
                
                logRecord("✅ 清空完成，UI模式: REALTIME");
            });
            
        } catch (Exception e) {
            logRecord("❌ 清空失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ⭐ 清理资源
     */
    public void cleanup() {
        unregisterUIUpdateEvents();
        if (player != null) {
            player.shutdown();
        }
        if (copyExecutor != null && !copyExecutor.isShutdown()) {
            copyExecutor.shutdownNow();
        }
        
        // ⭐ 清理图像缓存
        reusableImage = null;
        frameUpdateCount = 0;
        
        System.out.println("🧹 Element2_2JpegController 资源已清理");
    }

    /**
     * 获取播放器实例（供外部控制）
     */
    public DiskJpegPlayer getPlayer() {
        return player;
    }
}

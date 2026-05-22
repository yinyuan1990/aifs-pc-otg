package com.acard.acard.ui;

import com.acard.acard.*;
import com.acard.acard.tools.FileToos;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.StackPane;
import javafx.scene.image.Image;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.*;

import com.acard.acard.storage.SlowmoStore;

/**
 * 慢放独立UI控制器：承载 SlowMoView、帧数显示、进度条与播放按钮。
 */
public class SlowMoPaneController implements Initializable {

    @FXML private StackPane videoContainer;
    @FXML private Label frameCountLabel;
    @FXML private Slider progressSlider;
    @FXML private javafx.scene.control.ComboBox<String> speedComboBox;

    private SlowMoView slowMoView;  // 慢放显示视图
    private SimpleWebRTCPlayerView playerView;
    private SlowMoPlayerDisk slowPlayer;
    private List<DiskFrameRingBuffer.FrameItem> snapshot;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SlowMoPaneTimer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> countFuture;
    private ScheduledFuture<?> liveFuture;
    private ScheduledFuture<?> progressFuture; // 快照播放进度与标签实时更新
    private int liveIndex = 0;
    // live 模式的调度周期（ms），用于动态调整倍数时复用
    private long livePeriodMs = 33L;
    // 倍数动态更新的防抖任务，避免频繁取消/重建导致可感知的停顿
    private ScheduledFuture<?> factorUpdateFuture;
    private int pendingFactor = -1;
    // 程序更新进度条时的保护标记，避免触发预览监听导致暂停播放
    private volatile boolean updatingProgress = false;
    // 文案模式：false=显示“下载: x/总帧数”，true=显示“进度: x/总帧数”
    private volatile boolean showProgressLabel = false;

    // 新增：记录最近一次下载帧数与采集中状态，供UI组合显示
    private volatile int latestDownloadCount = 0;
    private volatile boolean latestCapturing = false;
    // 总帧容量：从 SlowmoStore 初始化；UI 范围 3000-10000
    private int capacity = SlowmoStore.getInstance().getSlowmoFrames();
    private int factor = 1; // 默认慢放倍数为1x，保证与实时速度同步
    // 动态下载速率（FPS）估算，以及计算用的时间戳与计数
    private volatile double currentDownloadRateFps = 0.0;
    private long lastRateTimeMs = 0L;
    private int lastRateCount = 0;
    // 拖动状态标记（用于避免拖动时更新进度条）
    private volatile boolean isDragging = false;
    // 录制状态标记（录制中不允许拖动滑块）
    private volatile boolean isRecording = false;

    public void setFactor(int factor) {
        this.factor = Math.max(1, Math.min(10, factor));
    }


    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 设置慢放总帧容量（用于UI显示与进度条范围）。
     * 允许范围：3000 - 10000。
     */
    public void setCapacity(int capacity) {
        int capped = Math.max(3000, Math.min(10000, capacity));
        this.capacity = capped;
        Platform.runLater(() -> {
            // ⚠️ 关键修复：最大值 = capacity（总帧数），不是 capacity - 1
            progressSlider.setMax(Math.max(0, capped));
            // 立即刷新"下载"文案，避免显示旧值
            frameCountLabel.setText(latestDownloadCount + " / " + capped);
        });
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        slowMoView = new SlowMoView();  // 创建SlowMoView
        slowMoView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        videoContainer.getChildren().setAll(slowMoView);
        
        
        // 进度条交互：拖动到某位置时进行预览或定位
        progressSlider.setMin(0);
        progressSlider.setValue(0);
        // ⚠️ 关键修复：最大值 = capacity（总帧数），不是 capacity - 1
        progressSlider.setMax(Math.max(0, capacity));


        // 在第110行之前添加新的拖动监听
        progressSlider.setOnMouseDragged(e -> {
            if (isRecording || updatingProgress) return;

            int previewFrame = (int) Math.round(progressSlider.getValue());
            previewFrame = Math.min(previewFrame, currentRecordedFrames);

            // 异步预览，不阻塞UI
            CompletableFuture.runAsync(() -> {
                if (mp4PlayerRef != null) {
                    // 轻量级预览：只更新UI，不实际seek
                    Platform.runLater(() -> {
                        frameCountLabel.setText(currentPlaybackFrame + " / " + currentRecordedFrames + " (预览)");
                    });
                }
            });
        });
        
        // ⚠️ 优化：只在拖动结束时seek，避免频繁操作
        progressSlider.setOnMousePressed(e -> {
            // ⚠️ 关键修复：录制中不允许拖动滑块
            if (isRecording) {
                System.out.println("⚠️ 录制中不允许拖动滑块");
                e.consume(); // 阻止事件
                return;
            }
            isDragging = true;
        });
        
        progressSlider.setOnMouseReleased(e -> {
            // ⚠️ 关键修复：录制中不允许拖动滑块
            if (isRecording) {
                System.out.println("⚠️ 录制中不允许拖动滑块");
                e.consume(); // 阻止事件
                return;
            }
            
            isDragging = false;
            if (updatingProgress) return;
            
            int idx = (int) Math.round(progressSlider.getValue());
            
            // ⚠️ 关键修复：限制在实际下载帧数范围内
            int actualIdx = Math.min(idx, currentRecordedFrames);
            if (actualIdx != idx) {
                System.out.println("⚠️ 拖动位置超出下载范围，限制为: " + actualIdx + " (下载帧数: " + currentRecordedFrames + ")");
            }
            
            // ✅ 优先使用MKV播放器（支持时间seek）

            CompletableFuture.runAsync(() -> {
                if (mp4PlayerRef != null) {
                    // MKV播放器：actualIdx是帧号，转换为时间（使用真实FPS）
                    int fps = getFpsFromConfig();
                    double timeInSeconds = actualIdx / (double)fps;
                    mp4PlayerRef.seekToTime(timeInSeconds);

                    // ⚠️ 关键修复：拖动后立即更新播放帧数（避免显示旧的帧数）
                    currentPlaybackFrame = actualIdx;
                    Platform.runLater(() -> {
                        if (frameCountLabel != null) {
                            frameCountLabel.setText(actualIdx + " / " + currentRecordedFrames);
                        }
                    });

                    System.out.println("📍 MKV播放跳转到: 第" + actualIdx + "帧 (" + String.format("%.1f", timeInSeconds) + "秒, FPS:" + fps + ", 下载: " + currentRecordedFrames + ")");
                } else if (livePlayerRef != null) {
                    livePlayerRef.seekToFrame(actualIdx);
                    System.out.println("📍 进度跳转到帧: " + actualIdx);
                } else if (slowMotionPlayerRef != null) {
                    slowMotionPlayerRef.seekToFrame(actualIdx);
                    System.out.println("📍 进度跳转到帧: " + actualIdx);
                } else {
                    // 旧逻辑：预览帧
                    previewIndex(actualIdx);
                }
            });
        });
        
        // ⚠️ 初始化速度控制下拉框：1x - 5x，步长0.5
        if (speedComboBox != null) {
            speedComboBox.getItems().addAll(
                "1.0x", "1.5x", "2.0x", "2.5x", "3.0x", 
                "3.5x", "4.0x", "4.5x", "5.0x"
            );
            
            // ✅ 从本地存储读取上次保存的速度，而不是固定使用 1.0x
            String savedSpeed = SlowmoStore.getInstance().getSlowmoSpeed();
            speedComboBox.setValue(savedSpeed);
            System.out.println("✅ 从本地读取慢放倍数: " + savedSpeed);
            
            // 监听速度变化
            speedComboBox.setOnAction(e -> {
                String selected = speedComboBox.getValue();
                if (selected != null && !selected.isEmpty()) {
                    try {
                        // 提取倍速值（去掉"x"）
                        double speed = Double.parseDouble(selected.replace("x", ""));
                        onSpeedChanged(speed);
                        
                        // ✅ 保存到本地存储
                        SlowmoStore.getInstance().setSlowmoSpeed(selected);
                    } catch (NumberFormatException ex) {
                        System.err.println("⚠️ 无效的速度值: " + selected);
                    }
                }
            });
            
            System.out.println("✅ 速度控制下拉框已初始化（1x - 5x，步长0.5）");
        }
    }

    public void setPlayer(SimpleWebRTCPlayerView player) {
            this.playerView = player;
            startCountTimer();
        }

        private void startCountTimer() {
            stopCountTimer();
            countFuture = scheduler.scheduleAtFixedRate(() -> {
                if (playerView == null) return;
                int count = playerView.getSlowMoDiskCount();
                List<DiskFrameRingBuffer.FrameItem> frames = null;
                try { frames = playerView.getSlowMoDiskSnapshot(); } catch (Throwable ignore) {}
                boolean capturing = false;
                try { capturing = playerView.isSlowMoCapturing(); } catch (Throwable ignore) {}
                final int countF = count;
                final boolean capturingF = capturing;
                // 估算下载速率FPS（基于计数差与时间差），并在 live 播放中动态调整调度周期
                try {
                    long now = System.currentTimeMillis();
                    if (lastRateTimeMs == 0L) {
                        lastRateTimeMs = now;
                        lastRateCount = count;
                    } else {
                        long dt = now - lastRateTimeMs;
                        int dc = count - lastRateCount;
                        if (dt >= 800) { // 至少 0.8s 更新一次，降低重排频率
                            double fps = (dc * 1000.0) / Math.max(1L, dt);
                            if (fps > 0.1) {
                                currentDownloadRateFps = Math.max(1.0, Math.min(240.0, fps));
                                lastRateTimeMs = now;
                                lastRateCount = count;
                                if (liveFuture != null) {
                                    long newBase = computeLiveBaseIntervalMs();
                                    long newPeriod = Math.max(10, (long) Math.round(newBase * this.factor));
                                    if (Math.abs(newPeriod - livePeriodMs) >= 2) {
                                        livePeriodMs = newPeriod;
                                        // 保持 liveIndex 不变地重建任务
                                        try { rescheduleLiveWithCurrentPeriod(); } catch (Throwable ignore) {}
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {}
                Platform.runLater(() -> {
                    // 持续记录下载进度与采集状态
                    latestDownloadCount = countF;
                    latestCapturing = capturingF;
                    // 下载阶段：仅在未切换为进度文案时显示"下载"标签
                    if (!showProgressLabel) {
                        frameCountLabel.setText(countF + " / " + capacity);
                    }
                });
            }, 0, 500, TimeUnit.MILLISECONDS);
        }

        private void stopCountTimer() {
            if (countFuture != null) {
                countFuture.cancel(false);
                countFuture = null;
            }
        }

        public void onPlayClicked() {
            setCapacity(SlowmoStore.getInstance().getSlowmoFrames());
            if (playerView == null) return;
            // 如果仍在采集中，改为 live 播放（边下载边播），实时更新总帧数
            boolean capturing = false;
            try { capturing = playerView.isSlowMoCapturing(); } catch (Throwable ignore) {}
            if (capturing) {
                showProgressLabel = true;
                startLivePlayback(this.factor);
                return;
            }
            // 快照模式：如果已有播放器（可能因拖动进度暂停），则从当前位置恢复播放
            if (slowPlayer != null) {
                showProgressLabel = true;
                // 确保有快照数据
                if (snapshot == null || snapshot.isEmpty()) {
                    snapshot = playerView.getSlowMoDiskSnapshot();
                    if (snapshot == null || snapshot.isEmpty()) {
                        System.err.println("SLOWMO_PANE: ❌ no frames to resume");
                        return;
                    }
                }
                // 继续播放（previewIndex 已经 seek 到了当前进度）
                slowPlayer.resume();
                // 如进度轮询未启动，则补充启动
                if (progressFuture == null) {
                    progressFuture = scheduler.scheduleAtFixedRate(() -> {
                        try {
                            int total = (snapshot != null) ? snapshot.size() : 0;
                            int idxCalculated;
                            try {
                                idxCalculated = Math.max(0, (slowPlayer != null ? slowPlayer.getIndex() : 0) - 1);
                            } catch (Throwable ignore) {
                                idxCalculated = 0;
                            }
                            int current = Math.min(total, idxCalculated + 1);

                            final int totalF = total;
                            final int idxF = idxCalculated;
                            final int currentF = current;

                            Platform.runLater(() -> {
                                progressSlider.setMax(Math.max(0, totalF - 1));
                                updatingProgress = true;
                                try { progressSlider.setValue(Math.max(0, idxF)); } finally { updatingProgress = false; }
                                if (showProgressLabel) {
                                    frameCountLabel.setText("下载进度: " + latestDownloadCount + "/" + capacity + "\n播放进度: " + currentF + "/" + totalF);
                                }
                            });
                        } catch (Throwable ignore) {}
                    }, 0, 100, TimeUnit.MILLISECONDS);
                }
                return;
            }
            // 否则新建播放器，并从当前进度位置开始播放
            if (liveFuture != null) {
                liveFuture.cancel(false);
                liveFuture = null;
            }
            if (progressFuture != null) {
                progressFuture.cancel(false);
                progressFuture = null;
            }
            showProgressLabel = true;
            snapshot = playerView.getSlowMoDiskSnapshot();
            if (snapshot == null || snapshot.isEmpty()) {
                System.err.println("SLOWMO_PANE: ❌ no frames in disk snapshot");
                return;
            }
            // 读取当前进度条位置或最近播放位置作为起始帧
            int startIdx = Math.max(0, Math.min(getCurrentPlaybackIndex(), snapshot.size() - 1));
            System.err.println("SLOWMO_PANE: ▶️ play from idx=" + startIdx + ", frames=" + snapshot.size() + ", factor=" + factor);
            slowPlayer = new SlowMoPlayerDisk(snapshot, factor, img -> slowMoView.renderImage(img));
            slowPlayer.seekToIndex(startIdx);
            // 先渲染当前帧，避免用户看到空白
            try {
                java.io.InputStream in = java.nio.file.Files.newInputStream(snapshot.get(startIdx).path);
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                in.close();
                if (bi != null) {
                    Image img = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
                    Platform.runLater(() -> slowMoView.renderImage(img));
                }
            } catch (Exception ignore) {}
            slowPlayer.start();
            // 启动进度轮询：更新进度条与标签（当前帧/总帧、时长、播放时长）
            progressFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    int total = (snapshot != null) ? snapshot.size() : 0;
                    int idxCalculated;
                    try {
                        idxCalculated = Math.max(0, (slowPlayer != null ? slowPlayer.getIndex() : 0) - 1);
                    } catch (Throwable ignore) {
                        idxCalculated = 0;
                    }
                    int current = Math.min(total, idxCalculated + 1);

                    final int totalF = total;
                    final int idxF = idxCalculated;
                    final int currentF = current;

                    Platform.runLater(() -> {
                        progressSlider.setMax(Math.max(0, totalF - 1));
                        updatingProgress = true;
                        try { progressSlider.setValue(Math.max(0, idxF)); } finally { updatingProgress = false; }
                        if (showProgressLabel) {
                            frameCountLabel.setText("下载进度: " + latestDownloadCount + "/" + capacity + "\n播放进度: " + currentF + "/" + totalF);
                        }
                    });
                } catch (Throwable ignore) {}
            }, 0, 100, TimeUnit.MILLISECONDS);
        }

        /**
         * 开始“边采集边播放”的live模式：点击开启慢放后立即显示底部画面。
         */
        public void startLivePlayback(int factor) {
            setCapacity(SlowmoStore.getInstance().getSlowmoFrames());
            this.factor = Math.max(1, Math.min(10, factor));
            // 停止快照播放器
            if (slowPlayer != null) {
                slowPlayer.stop();
                slowPlayer = null;
            }
            // 取消旧的live任务
            if (liveFuture != null) {
                liveFuture.cancel(false);
                liveFuture = null;
            }
            if (progressFuture != null) {
                progressFuture.cancel(false);
                progressFuture = null;
            }
            liveIndex = 0;
            System.err.println("SLOWMO_PANE: ▶️ live start, factor=" + this.factor);
            long baseInterval = computeLiveBaseIntervalMs();
            livePeriodMs = Math.max(10, baseInterval * this.factor);
            // live播放时统一展示进度文案，避免与下载文案抢写造成跳动
            showProgressLabel = true;
            liveFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (playerView == null) return;
                    List<DiskFrameRingBuffer.FrameItem> frames = playerView.getSlowMoDiskSnapshot();
                    if (frames == null || frames.isEmpty()) return;
                    int idx;
                    if (this.factor == 1) {
                        // 1x：直接跟随最新帧
                        idx = frames.size() - 1;
                        if (idx < 0) return;
                        liveIndex = frames.size();
                    } else {
                        if (liveIndex >= frames.size()) return; // 等待新帧
                        idx = liveIndex++;
                    }
                    DiskFrameRingBuffer.FrameItem item = frames.get(idx);
                    try (java.io.InputStream in = java.nio.file.Files.newInputStream(item.path)) {
                        java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                        if (bi != null) {
                            Image img = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
                            Platform.runLater(() -> slowMoView.renderImage(img));
                        }
                    } catch (java.io.IOException ignore) {}
                    // 更新UI进度：当前指针/总下载帧数
                    int total = frames.size();
                    int current = Math.min(total, Math.max(1, Math.max(1, liveIndex)));
                    Platform.runLater(() -> {
                        // live 播放：进度条最大值 = 实际总帧数 - 1（已下载帧数）
                        progressSlider.setMax(Math.max(0, total - 1));
                        updatingProgress = true;
                        try { progressSlider.setValue(Math.max(0, Math.max(0, liveIndex - 1))); } finally { updatingProgress = false; }
                        if (showProgressLabel) {
                            // 播放进度分母 = 实际总帧数（已下载帧数）
                            frameCountLabel.setText("下载进度: " + latestDownloadCount + "/" + capacity + "\n播放进度: " + current + "/" + total);
                        }
                    });
                } catch (Exception ignore) {}
            }, 0, livePeriodMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 动态更新慢放倍数：
         * - 快照播放：直接设置 SlowMoPlayerDisk 的倍数；
         * - live 播放：重新按新周期调度，不重置 liveIndex。
         */
        public void updateFactor(int newFactor) {
            int clamped = Math.max(1, Math.min(10, newFactor));
            // 若倍数未变化，直接返回
            if (this.factor == clamped) return;
            this.factor = clamped;
            // 快照模式：调整播放器的调度周期
            if (slowPlayer != null) {
                try {
                    slowPlayer.setFactor(clamped);
                    System.err.println("SLOWMO_PANE: ⏱ snapshot factor update -> " + clamped);
                } catch (Throwable ignore) {}
                return;
            }
            // live 模式：重建调度任务，但保持当前索引不变
            if (liveFuture != null) {
                try {
                    // 防抖：聚合快速滑动操作，减少取消/重建次数，避免肉眼可感知的停顿
                    pendingFactor = clamped;
                    if (factorUpdateFuture != null) {
                        factorUpdateFuture.cancel(false);
                        factorUpdateFuture = null;
                    }
                    factorUpdateFuture = scheduler.schedule(() -> {
                        int pf = pendingFactor;
                        pendingFactor = -1;
                        long baseIv = computeLiveBaseIntervalMs();
                        livePeriodMs = Math.max(10, baseIv * pf);
                        // 使用新的周期重建任务，但不重置 liveIndex
                        try {
                            liveFuture.cancel(false);
                        } catch (Throwable ignore) {}
                        liveFuture = scheduler.scheduleAtFixedRate(() -> {
                            try {
                                if (playerView == null) return;
                                List<DiskFrameRingBuffer.FrameItem> frames = playerView.getSlowMoDiskSnapshot();
                                if (frames == null || frames.isEmpty()) return;
                                int idx2;
                                if (pf == 1) {
                                    idx2 = frames.size() - 1;
                                    if (idx2 < 0) return;
                                    liveIndex = frames.size();
                                } else {
                                    if (liveIndex >= frames.size()) return; // 等待新帧
                                    idx2 = liveIndex++;
                                }
                                DiskFrameRingBuffer.FrameItem item = frames.get(idx2);
                                try (java.io.InputStream in = java.nio.file.Files.newInputStream(item.path)) {
                                    java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                                    if (bi != null) {
                                        Image img = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
                                        Platform.runLater(() -> slowMoView.renderImage(img));
                                    }
                                } catch (java.io.IOException ignore) {}
                                // 新增：切换倍速后仍然实时刷新进度条与标签，避免 UI 冻结
                                int total2 = frames.size();
                                int current2 = Math.min(total2, Math.max(1, Math.max(1, liveIndex)));
                                Platform.runLater(() -> {
                                    // live播放：进度条最大值 = 已下载的实际总帧数 - 1
                                    progressSlider.setMax(Math.max(0, total2 - 1));
                                    updatingProgress = true;
                                    try { progressSlider.setValue(Math.max(0, Math.max(0, liveIndex - 1))); } finally { updatingProgress = false; }
                                    if (showProgressLabel) {
                                        // 播放进度分母 = 实际总帧数（已下载帧数）
                                        frameCountLabel.setText("下载进度: " + latestDownloadCount + "/" + capacity + "\n播放进度: " + current2 + "/" + total2);
                                    }
                                });
                            } catch (Exception ignore) {}
                        }, 0, livePeriodMs, TimeUnit.MILLISECONDS);
                        System.err.println("SLOWMO_PANE: ⏱ live factor update -> " + pf + ", period=" + livePeriodMs + "ms");
                    }, 60, TimeUnit.MILLISECONDS);
                } catch (Throwable ignore) {}
            }
        }

        // 计算 live 播放的基准周期（ms）：优先使用最近1秒内下载速率推导，回退为采集周期
        private long computeLiveBaseIntervalMs() {
            double fps = currentDownloadRateFps;
            if (fps > 0.5) {
                return Math.max(10L, (long) Math.round(1000.0 / fps));
            }
            long baseInterval = 33L;
            try { if (playerView != null) baseInterval = Math.max(10, playerView.getSlowCaptureIntervalMs()); } catch (Throwable ignore) {}
            return baseInterval;
        }

        // 在不重置 liveIndex 的情况下，使用当前 livePeriodMs 重建 live 播放任务
        private void rescheduleLiveWithCurrentPeriod() {
            if (liveFuture != null) {
                try { liveFuture.cancel(false); } catch (Throwable ignore) {}
            }
            liveFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (playerView == null) return;
                    List<DiskFrameRingBuffer.FrameItem> frames = playerView.getSlowMoDiskSnapshot();
                    if (frames == null || frames.isEmpty()) return;
                    int idx;
                    if (this.factor == 1) {
                        idx = frames.size() - 1;
                        if (idx < 0) return;
                        liveIndex = frames.size();
                    } else {
                        if (liveIndex >= frames.size()) return; // 等待新帧
                        idx = liveIndex++;
                    }
                    DiskFrameRingBuffer.FrameItem item = frames.get(idx);
                    try (java.io.InputStream in = java.nio.file.Files.newInputStream(item.path)) {
                        java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                        if (bi != null) {
                            Image img = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
                            Platform.runLater(() -> slowMoView.renderImage(img));
                        }
                    } catch (java.io.IOException ignore) {}
                    // 更新UI进度
                    int total = frames.size();
                    int current = Math.min(total, Math.max(1, Math.max(1, liveIndex)));
                    Platform.runLater(() -> {
                        progressSlider.setMax(Math.max(0, total - 1));
                        updatingProgress = true;
                        try { progressSlider.setValue(Math.max(0, Math.max(0, liveIndex - 1))); } finally { updatingProgress = false; }
                        if (showProgressLabel) {
                            frameCountLabel.setText("下载进度: " + latestDownloadCount + "/" + capacity + "\n播放进度: " + current + "/" + total);
                        }
                    });
                } catch (Exception ignore) {}
            }, 0, livePeriodMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 当前播放帧索引：
         * - 快照模式：返回当前播放器索引的已渲染帧（getIndex()-1，下限为0）
         * - live 模式：返回最近已渲染帧（liveIndex-1，下限为0）
         * - 未播放：退化为进度条当前值
         */
        public int getCurrentPlaybackIndex() {
            try {
                if (slowPlayer != null) {
                    int idx = Math.max(0, slowPlayer.getIndex() - 1);
                    return idx;
                }
                if (liveFuture != null) {
                    return Math.max(0, liveIndex - 1);
                }
                return Math.max(0, (int) Math.round(progressSlider.getValue()));
            } catch (Throwable ignore) {
                return 0;
            }
        }

        private void previewIndex(int idx) {
            if (snapshot == null || snapshot.isEmpty()) {
                snapshot = playerView != null ? playerView.getSlowMoDiskSnapshot() : null;
            }
            if (snapshot == null || snapshot.isEmpty()) return;
            idx = Math.max(0, Math.min(idx, snapshot.size() - 1));
            // 暂停播放并渲染该索引帧
            if (slowPlayer != null) {
                slowPlayer.pause();
                slowPlayer.seekToIndex(idx);
                Image img = slowPlayer.renderIndexOnce(idx);
                if (img != null) slowMoView.renderImage(img);
            } else {
                // 未创建播放器时，直接读取磁盘进行一次渲染
                try {
                    java.io.InputStream in = java.nio.file.Files.newInputStream(snapshot.get(idx).path);
                    java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                    in.close();
                    if (bi != null) {
                        Image img = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
                        slowMoView.renderImage(img);
                    }
                } catch (Exception ignore) {}
            }
        }

        public void dispose() {
            stopCountTimer();
            if (slowPlayer != null) {
                slowPlayer.stop();
                slowPlayer = null;
            }
            if (liveFuture != null) {
                liveFuture.cancel(false);
                liveFuture = null;
            }
            if (progressFuture != null) {
                progressFuture.cancel(false);
                progressFuture = null;
            }
        }

        /**
         * 复位慢放面板到空白状态：
         * - 停止所有播放/计时任务
         * - 清空画布
         * - 重置进度条到0
         * - 更新标签为进度 0/容量
         */
        public void resetToEmptyState() {
            // 停止所有任务
            dispose();
            // 取消倍数防抖任务
            if (factorUpdateFuture != null) {
                try { factorUpdateFuture.cancel(false); } catch (Throwable ignore) {}
                factorUpdateFuture = null;
            }
            pendingFactor = -1;
            snapshot = null;
            liveIndex = 0;
            // 重置为仅显示“下载进度”文案（未播放态）
            showProgressLabel = false;
            Platform.runLater(() -> {
                try {
                    if (slowMoView != null) slowMoView.clear();
                    // 复位播放进度条：先设最大值为0，再设值为0，避免越界抖动
                    progressSlider.setMax(0);
                    updatingProgress = true;
                    try { progressSlider.setValue(0); } finally { updatingProgress = false; }
                    // 根据文案模式更新标签
                    frameCountLabel.setText("0 / " + capacity);
                 } catch (Throwable ignore) {}
            });
        }

        /**
         * 开启慢放采集时，立即将“下载进度”归零但不停止计时器/任务。
         * 仅更新UI与latestDownloadCount，确保用户看到从0开始的下载。
         */
        public void resetDownloadProgressImmediate() {
            latestDownloadCount = 0;
            // 开始采集时回到“下载”文案模式
            showProgressLabel = false;
            // 若计数任务已被取消（比如播放/清空流程），则在再次开始采集时重启
            if (countFuture == null) {
                // 重置下载速率估算状态，避免继承上一次的live周期
                lastRateTimeMs = 0L;
                lastRateCount = 0;
                startCountTimer();
            }
            Platform.runLater(() -> {
                try {
                    if (frameCountLabel != null) {
                        frameCountLabel.setText("0 / " + capacity);
                    }
                } catch (Throwable ignore) {}
            });
        }

        // 计算时长：使用第一帧到最后一帧的时间戳差值
        private long computeDurationMs(List<DiskFrameRingBuffer.FrameItem> frames) {
            try {
                if (frames == null || frames.size() < 2) return 0L;
                long start = frames.get(0).timestamp;
                long end = frames.get(frames.size() - 1).timestamp;
                long dur = Math.max(0L, end - start);
                return Math.min(10_000_000L, dur); // 保护性上限
            } catch (Throwable ignore) { return 0L; }
        }

        private String formatDuration(long ms) {
            if (ms <= 0) return "0.0s";
            double sec = ms / 1000.0;
            return String.format("%.1fs", sec);
        }
        
    // 🎬 慢动作播放器引用（用于进度跳转）
    private com.acard.acard.slowmotion.SlowMotionPlayer slowMotionPlayerRef;
    private com.acard.acard.slowmotion.DiskBasedSlowMotionPlayer livePlayerRef;
    private com.acard.acard.capture.DiskCaptureCache captureCacheRef;
    
    // 🎬 MP4播放器引用（用于边录边播）
    private com.acard.acard.slowmotion.SlowMoGpuPlayer mp4PlayerRef;
    private volatile int currentRecordedFrames = 0;  // 当前录制的帧数
    
    /**
     * 获取慢放播放器引用（用于外部控制）
     */
    public com.acard.acard.slowmotion.DiskBasedSlowMotionPlayer getLivePlayer() {
        return livePlayerRef;
    }
    
    /**
     * 显示一帧画面（用于新慢动作系统）
     */
    public void displayFrame(Image image) {
        if (slowMoView != null) {
            slowMoView.renderImage(image);
        }
    }
    
    /**
     * 清空视频画面（避免显示上一次的画面）
     */
    public void clearVideo() {
        if (slowMoView != null) {
            slowMoView.clearImage();
        }
    }
    
    /**
     * 更新播放进度（用于新慢动作系统）
     */
    public void updateProgress(int currentFrame, int totalFrames) {
        updateProgress(currentFrame, totalFrames, null);
    }
    
    /**
     * 更新播放进度（显示文件名）
     * ⚠️ 注意：此方法用于旧的慢放系统，MKV播放器不使用此方法
     */
    public void updateProgress(int currentFrame, int totalFrames, String fileName) {
        // ⚠️ 关键修复：如果是MKV播放器，不更新进度条（避免冲突）
        // MKV播放器的进度条由 updateRecordedFrames() 控制
        if (mp4PlayerRef != null) {
            // MKV播放器模式：不更新进度条，只更新文本（如果需要）
            return;
        }
        
        // 旧的慢放系统：更新文本和进度条
        if (frameCountLabel != null) {
            if (fileName != null && !fileName.isEmpty()) {
                frameCountLabel.setText("慢放: " + fileName + "\n进度: " + currentFrame + "/" + totalFrames);
            } else {
                frameCountLabel.setText("进度: " + currentFrame + "/" + totalFrames);
            }
        }
        if (progressSlider != null && totalFrames > 0) {
            updatingProgress = true;
            progressSlider.setMax(totalFrames - 1);
            progressSlider.setValue(currentFrame);
            updatingProgress = false;
        }
    }
    
    /**
     * 设置慢动作播放器引用（用于进度跳转）
     */
    public void setSlowMotionPlayer(com.acard.acard.slowmotion.SlowMotionPlayer player) {
        this.slowMotionPlayerRef = player;
        System.out.println("✅ SlowMoPaneController已绑定慢动作播放器，支持进度跳转");
    }
    
    /**
     * 设置MP4播放器引用（用于边录边播）
     */
    public void setMp4Player(com.acard.acard.slowmotion.SlowMoGpuPlayer player) {
        this.mp4PlayerRef = player;
        System.out.println("✅ SlowMoPaneController已绑定MP4播放器");
    }
    
    /**
     * 获取MP4播放器引用
     */
    public com.acard.acard.slowmotion.SlowMoGpuPlayer getMp4Player() {
        return mp4PlayerRef;
    }
    
    /**
     * 获取视频显示容器（用于MP4播放器绑定）
     */
    public StackPane getVideoPane() {
        return videoContainer;
    }
    
    /**
     * 设置录制状态（控制滑块是否可拖动）
     */
    public void setRecording(boolean recording) {
        this.isRecording = recording;
        System.out.println("📝 录制状态已设置: " + (recording ? "录制中（滑块禁用）" : "已停止（滑块启用）"));
        
        // ⚠️ 关键修复：禁用/启用滑块（视觉反馈）
        Platform.runLater(() -> {
            if (progressSlider != null) {
                progressSlider.setDisable(recording);
            }
        });
    }
    
    /**
     * 初始化UI元素（用于慢放开始时重置状态）
     */
    public void initializeSlowMotionUI() {
        // ⚠️ 关键修复：重置播放帧数和录制帧数（避免显示上一次的数据）
        currentPlaybackFrame = 0;
        currentRecordedFrames = 0;
        lastPlaybackFrameUpdate = -1;
        
        // ⚠️ 关键修复：开始录制，禁用滑块
        isRecording = true;
        
        Platform.runLater(() -> {
            if (frameCountLabel != null) {
                // ✅ 显示：播放帧数 / 录制帧数
                frameCountLabel.setText("0 / 0");
            }
            if (progressSlider != null) {
                updatingProgress = true;
                progressSlider.setValue(0);
                progressSlider.setMax(Math.max(1, capacity)); // 最大值 = capacity
                progressSlider.setDisable(true); // 录制中禁用滑块
                updatingProgress = false;
            }
        });
    }
    
    /**
     * 更新录制帧数显示（用于MKV边录边播）
     */
    // ========== 播放帧数（当前播放位置） ==========
    private volatile int currentPlaybackFrame = 0;
    
    public void updateRecordedFrames(int frames) {
        currentRecordedFrames = frames;
        // ⚠️ 关键修复：UI更新完全异步，不阻塞播放
        Platform.runLater(() -> {
            if (frameCountLabel != null) {
                // ✅ 显示：播放帧数/录制帧数（录制帧数实时变化）
                frameCountLabel.setText(currentPlaybackFrame + " / " + frames);
            }
            // ✅ 进度条：播放帧数/录制帧数（动态最大值，避免抖动）
            // 最大值 = 录制帧数（动态增长），当前值 = 播放帧数
            // 这样进度条始终显示：当前播放位置 / 已录制内容
            if (progressSlider != null && !isDragging) {
                updatingProgress = true;
                progressSlider.setMax(Math.max(1, frames)); // 最大值 = 录制帧数
                progressSlider.setValue(Math.min(currentPlaybackFrame, frames)); // 当前值 = 播放帧数
                updatingProgress = false;
            }
        });
    }
    
    /**
     * 更新播放帧数（当前播放到第几帧）
     */
    private int lastPlaybackFrameUpdate = -1;
    
    public void updatePlaybackFrame(int frame) {
        // ⚠️ 关键修复：播放帧数不能超过实际下载帧数

        currentPlaybackFrame = Math.min(frame, currentRecordedFrames);
        
        // ⚠️ 关键修复：立即更新UI，确保播放帧数和画面同步
        Platform.runLater(() -> {
            if (frameCountLabel != null) {
                frameCountLabel.setText(currentPlaybackFrame + " / " + currentRecordedFrames);
            }
            // ✅ 同步更新进度条（如果不在拖动中）
            if (progressSlider != null && !isDragging) {
                updatingProgress = true;
                progressSlider.setValue(currentPlaybackFrame);
                updatingProgress = false;
            }
        });
        
        lastPlaybackFrameUpdate = currentPlaybackFrame;
    }
    
    /**
     * 从ConfigStore读取真实FPS
     */
    private int getFpsFromConfig() {
        try {
            com.acard.acard.model.ThinRemoteConfig cfg = com.acard.acard.storage.ConfigStore.getInstance().getThinConfig();
            if (cfg != null && cfg.getFps() != null) {
                return cfg.getFps();
            }
        } catch (Exception e) {
            System.err.println("⚠️ 读取FPS失败: " + e.getMessage());
        }
        return 30;  // 默认30fps
    }
    
    /**
     * 设置基于磁盘缓存的播放器（用于边录边播）
     */
    public void setSlowMotionPlayerLive(com.acard.acard.slowmotion.DiskBasedSlowMotionPlayer player) {
        this.livePlayerRef = player;
        System.out.println("✅ SlowMoPaneController已绑定边录边播播放器，支持进度跳转");
    }
    
    /**
     * 设置缓存引用（用于清理）
     */
    public void setCaptureCache(com.acard.acard.capture.DiskCaptureCache cache) {
        this.captureCacheRef = cache;
    }
    
    /**
     * 设置播放器引用（用于停止）
     */
    public void setLivePlayer(com.acard.acard.slowmotion.DiskBasedSlowMotionPlayer player) {
        this.livePlayerRef = player;
    }
    
    /**
     * 停止边录边播播放器（✅ 修复内存泄漏：清理帧缓存）
     */
    public void stopLivePlayer() {
        if (livePlayerRef != null) {
            System.out.println("🛑 停止慢放播放器并清理资源...");
            livePlayerRef.stop();
            livePlayerRef.cleanup();  // ✅ 清理frameCache（30帧 ≈ 110MB），释放内存
            livePlayerRef = null;
            System.out.println("✅ 慢放播放器资源已释放");
        }
        
        // ✅ 清理SlowMoView的lastImage，释放Image引用
        if (slowMoView != null) {
            slowMoView.clear();  // 内部会调用 System.gc()
            System.out.println("✅ 慢放显示已清理");
        }
    }
    
    /**
     * 清理显示
     */
    public void clearDisplay() {
        if (slowMoView != null) {
            slowMoView.clear();
        }
        if (frameCountLabel != null) {
            frameCountLabel.setText("进度: 0/0");
        }
        if (progressSlider != null) {
            updatingProgress = true;
            progressSlider.setValue(0);
            updatingProgress = false;
        }
    }


    // 第994-1014行修改
    private void onSpeedChanged(double speed) {
        System.out.println("🎬 用户调节播放速度: " + speed + "x");

        FileToos.slowspeed =speed;

        if (mp4PlayerRef != null) {
            // ⚠️ 关键修复：检查播放器状态，避免在暂停状态下调用setRate
            if (!mp4PlayerRef.isPlaying()) {
                System.out.println("⚠️ 播放器未在播放状态，跳过速度调整");
                System.out.println("💡 请先点击播放按钮，然后再调整速度");
                return;
            }

            double playbackRate = 1.0 / speed;
            try {
                mp4PlayerRef.setRate(playbackRate);
                System.out.println("✅ MKV播放速度已设置: " + speed + "x慢放 (实际播放速度: " + playbackRate + ")");
            } catch (Exception e) {
                System.err.println("❌ 设置播放速度失败: " + e.getMessage());
            }
        } else if (livePlayerRef != null) {
            // 其他播放器的处理保持不变
            livePlayerRef.setSlowMotionFactor((int)speed);
            System.out.println("✅ 慢放速度已更新: " + speed + "x");
        } else if (slowMotionPlayerRef != null) {
            slowMotionPlayerRef.setSlowMotionFactor((int)speed);
            System.out.println("✅ 慢放速度已更新: " + speed + "x");
        } else {
            System.out.println("⚠️ 没有可用的播放器");
        }
    }
    
    /**
     * 速度变化回调（从下拉框触发）
     * @param speed 播放速度（1.0 - 5.0）
     */
    /*private void onSpeedChanged(double speed) {
        System.out.println("🎬 用户调节播放速度: " + speed + "x");
        
        if (mp4PlayerRef != null) {
            // ⚠️ 注意：这里的speed是实际播放速度，不是慢放倍数
            // 1.0x = 正常速度，2.0x = 2倍速（快放），0.5x = 0.5倍速（慢放）
            // 但是用户选择的是"慢放倍数"的概念，所以需要转换：
            // 用户选1.0x → 实际播放速度 = 1.0 / 1.0 = 1.0（正常速度）
            // 用户选2.0x → 实际播放速度 = 1.0 / 2.0 = 0.5（慢放2倍）
            double playbackRate = 1.0 / speed;
            mp4PlayerRef.setRate(playbackRate);
            System.out.println("✅ MKV播放速度已设置: " + speed + "x慢放 (实际播放速度: " + playbackRate + ")");
        } else if (livePlayerRef != null) {
            livePlayerRef.setSlowMotionFactor((int)speed);
            System.out.println("✅ 慢放速度已更新: " + speed + "x");
        } else if (slowMotionPlayerRef != null) {
            slowMotionPlayerRef.setSlowMotionFactor((int)speed);
            System.out.println("✅ 慢放速度已更新: " + speed + "x");
        } else {
            System.out.println("⚠️ 没有可用的播放器");
        }
    }*/
    
    /**
     * 动态更新慢放速度
     * 
     * @param factor 慢放倍数（1-10）
     */
    public void updateSlowMotionSpeed(int factor) {
        if (mp4PlayerRef != null) {
            // ✅ 更新GPU+OpenGL播放器的速度
            double playbackRate = 1.0 / factor;  // 倍数越大，速度越慢
            mp4PlayerRef.setRate(playbackRate);
            System.out.println("🎬 MKV慢放速度已更新: " + factor + "x (播放速度: " + playbackRate + ")");
        } else if (livePlayerRef != null) {
            livePlayerRef.setSlowMotionFactor(factor);
            System.out.println("🎬 慢放速度已更新: " + factor + "x");
        } else if (slowMotionPlayerRef != null) {
            slowMotionPlayerRef.setSlowMotionFactor(factor);
            System.out.println("🎬 慢放速度已更新: " + factor + "x");
        }
    }
    
    /**
     * 检查是否正在慢放
     */
    public boolean isSlowMotionActive() {
        System.out.println("   📊 isSlowMotionActive() 检查:");
        
        // ⚠️ 关键修复：优先检查MKV播放器
        System.out.println("      mp4PlayerRef (MKV): " + (mp4PlayerRef != null ? "存在" : "null"));
        if (mp4PlayerRef != null) {
            boolean playing = mp4PlayerRef.isPlaying();
            System.out.println("      mp4PlayerRef.isPlaying(): " + playing);
            if (playing) {
                System.out.println("      返回结果: true (MKV播放器活跃)");
                return true;
            }
        }
        
        System.out.println("      livePlayerRef: " + (livePlayerRef != null ? "存在" : "null"));
        if (livePlayerRef != null) {
            boolean playing = livePlayerRef.isPlaying();
            System.out.println("      livePlayerRef.isPlaying(): " + playing);
        }
        System.out.println("      slowMotionPlayerRef: " + (slowMotionPlayerRef != null ? "存在" : "null"));
        if (slowMotionPlayerRef != null) {
            boolean playing = slowMotionPlayerRef.isPlaying();
            System.out.println("      slowMotionPlayerRef.isPlaying(): " + playing);
        }
        
        boolean result = (livePlayerRef != null && livePlayerRef.isPlaying()) || 
                        (slowMotionPlayerRef != null && slowMotionPlayerRef.isPlaying());
        System.out.println("      返回结果: " + result);


        return result;
    }
    
    /**
     * 获取当前播放帧文件路径（唯一标识，用于慢放抓拍）
     */
    public String getCurrentPlayingFramePath() {
        if (livePlayerRef != null) {
            return livePlayerRef.getLastDisplayedFramePath();
        } else if (slowMotionPlayerRef != null) {
            // TODO: 旧版播放器也需要支持
            return null;
        }
        return null;
    }
    
    /**
     * 获取慢放磁盘帧列表（用于慢放抓拍）
     */
    public List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> getSlowMotionDiskFrames() {
        if (captureCacheRef != null) {
            return captureCacheRef.getFrames();
        }
        return null;
    }
    
    /**
     * 获取当前播放帧号（用于MKV抓拍）
     */
    public int getCurrentPlaybackFrameIndex() {
        return currentPlaybackFrame;
    }
    
    /**
     * 获取MKV播放器引用（用于MKV抓拍）
     */
    public com.acard.acard.slowmotion.SlowMoGpuPlayer getMkvPlayerRef() {
        return mp4PlayerRef;
    }
}
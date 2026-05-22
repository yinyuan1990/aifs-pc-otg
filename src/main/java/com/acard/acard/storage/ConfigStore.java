package com.acard.acard.storage;

import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.net.ApiResponse;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.ThinConfigResponse;

import javafx.application.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 全局配置缓存（单例）
 * - 缓存 ThinRemoteConfig 供主界面及其他模块使用
 * - 提供预取方法：在连接 STOMP 前先通过 HTTP 获取并缓存
 * - 支持变更检测与全局UI监听（延迟2秒通知）
 */
public class ConfigStore {
    private static volatile ConfigStore instance;

    private ThinRemoteConfig currentThinConfig;
    // 监听器与调度器
    private final List<Consumer<ThinRemoteConfig>> listeners = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ConfigStore() {}

    public static ConfigStore getInstance() {
        if (instance == null) {
            synchronized (ConfigStore.class) {
                if (instance == null) instance = new ConfigStore();
            }
        }
        return instance;
    }

    public synchronized ThinRemoteConfig getThinConfig() {
        return currentThinConfig;
    }

    // 比较核心字段是否存在差异
    private boolean hasDifferences(ThinRemoteConfig a, ThinRemoteConfig b) {
        if (a == b) return false;
        if (a == null || b == null) return true;
        boolean diff = false;
        diff |= notEq(a.getDeviceId(), b.getDeviceId());
        diff |= notEq(a.getType(), b.getType());
        diff |= notEq(a.getPtype(), b.getPtype());
        diff |= notEq(a.getDirection(), b.getDirection());
        diff |= notEq(a.getZoom(), b.getZoom());
        diff |= notEq(a.getFps(), b.getFps());
        // 新增比较：码率与角度
        diff |= notEq(a.getBitrate(), b.getBitrate());
        diff |= notEq(a.getAngle(), b.getAngle());
        diff |= notEq(a.getExposureBias(), b.getExposureBias());
        diff |= notEq(a.getLastUpdated(), b.getLastUpdated());
        diff |= notEq(a.getUpdatedBy(), b.getUpdatedBy());
        return diff;
    }
    private static boolean notEq(Object x, Object y) {
        return (x == null) ? (y != null) : !x.equals(y);
    }

    public synchronized void setThinConfig(ThinRemoteConfig cfg) {
        boolean changed = hasDifferences(this.currentThinConfig, cfg);
        this.currentThinConfig = cfg;
        if (changed) {
            // 延迟2秒在JavaFX主线程通知所有订阅者
            final List<Consumer<ThinRemoteConfig>> snapshot = new ArrayList<>(listeners);
            scheduler.schedule(() -> Platform.runLater(() -> {
                for (Consumer<ThinRemoteConfig> l : snapshot) {
                    try { l.accept(cfg); } catch (Exception ignore) {}
                }
            }), 2, TimeUnit.SECONDS);
        }
    }

    // 订阅/取消订阅全局配置更新事件
    public synchronized void addThinConfigListener(Consumer<ThinRemoteConfig> listener) {
        if (listener != null) listeners.add(listener);
    }
    public synchronized void removeThinConfigListener(Consumer<ThinRemoteConfig> listener) {
        if (listener != null) listeners.remove(listener);
    }

    /**
     * 预取简化配置并写入缓存
     * @param deviceId 设备ID
     * @return Future，完成后缓存可用
     */
    public CompletableFuture<ThinRemoteConfig> prefetchThinConfig(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        System.err.println("获取简化配置成功:=============> " + deviceId);
        // 直接 GET 后端包装结构，再取 data
        return NetworkManager.getInstance()
                .get("/api/thin-config/" + deviceId, ThinConfigResponse.class)
                .thenApply((ApiResponse<ThinConfigResponse> resp) -> {


                    if (resp != null && resp.isSuccess() && resp.getData() != null && resp.getData().isSuccess()) {
                        ThinRemoteConfig cfg = resp.getData().getData();
                        try {
                            System.err.println("[ThinConfig] parsed -> deviceId=" + cfg.getDeviceId()
                                    + ", type=" + cfg.getType()
                                    + ", direction=" + cfg.getDirection()
                                    + ", zoom=" + cfg.getZoom()
                                    + ", fps=" + cfg.getFps()
                                    + ", bitrate=" + cfg.getBitrate()
                                    + ", angle=" + cfg.getAngle()
                                    + ", exposureBias=" + cfg.getExposureBias());
                            
                        } catch (Exception ignore) {}
                        setThinConfig(cfg);
                        return cfg;
                    } else {
                        System.err.println("获取简化配置失败: " + (resp != null ? resp.getMessage() : "未知错误"));
                        return null;
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("HTTP获取简化配置异常: " + ex.getMessage());
                    return null;
                });
    }
}
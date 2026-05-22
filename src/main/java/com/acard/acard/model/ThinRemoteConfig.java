package com.acard.acard.model;

import com.google.gson.annotations.SerializedName;

/**
 * 设备简化配置（与后端 /api/thin-config/{deviceId} 返回的 data 字段对应）
 * - 仅包含档位、初始焦距、相机方向等轻量字段
 */
public class ThinRemoteConfig {
    @SerializedName("device_id")
    private String deviceId;
    private String type;           // "standard" 或 "high"
    private Double zoom;           // 初始焦距倍率
    private String ptype;          // 预设类型或自定义标识
    private String direction;      // "-1" 前置 / "1" 后置
    @SerializedName("fps")
    private Integer fps;           // 帧率

    @SerializedName("cjfps")
    private Integer cjfps;         // ✅ 图像闪烁 [0, 100]（数字越高拖影越低）

    @SerializedName("bitrate")
    private int bitrate;      //码率

    @SerializedName("angle")
    private int angle;     //相机旋转

    @SerializedName("exposureBias")
    private Float exposureBias;    // 曝光补偿 EV [-2, 2]
    
    // ✅ 新增：4个相机控制参数
    @SerializedName("focus")
    private Float focus;           // 对焦距离 [0.0, 1.0]
    @SerializedName("brightness")
    private Float brightness;      // 亮度 [-1.0, 1.0]
    @SerializedName("saturation")
    private Float saturation;      // 饱和度 [0.0, 2.0]
    @SerializedName("contrast")
    private Float contrast;        // 对比度/色彩 [0.0, 4.0]
    
    @SerializedName("last_updated")
    private String lastUpdated;    // 字符串时间，保持简单解析
    @SerializedName("updated_by")
    private String updatedBy;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Double getZoom() { return zoom; }
    public void setZoom(Double zoom) { this.zoom = zoom; }
    public String getPtype() { return ptype; }
    public void setPtype(String ptype) { this.ptype = ptype; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getFps() { return fps; }
    public void setFps(Integer fps) { this.fps = fps; }
    
    // ✅ 图像闪烁 getter/setter
    public Integer getCjfps() { return cjfps; }
    public void setCjfps(Integer cjfps) { this.cjfps = cjfps; }
    public Float getExposureBias() { return exposureBias; }
    public void setExposureBias(Float exposureBias) { this.exposureBias = exposureBias; }
    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public int getBitrate() {
        return bitrate;
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    public int getAngle() {
        return angle;
    }

    public void setAngle(int angle) {
        this.angle = angle;
    }
    
    // ✅ 新增：4个相机控制参数的 getter/setter
    public Float getFocus() {
        return focus;
    }
    
    public void setFocus(Float focus) {
        this.focus = focus;
    }
    
    public Float getBrightness() {
        return brightness;
    }
    
    public void setBrightness(Float brightness) {
        this.brightness = brightness;
    }
    
    public Float getSaturation() {
        return saturation;
    }
    
    public void setSaturation(Float saturation) {
        this.saturation = saturation;
    }
    
    public Float getContrast() {
        return contrast;
    }
    
    public void setContrast(Float contrast) {
        this.contrast = contrast;
    }
}
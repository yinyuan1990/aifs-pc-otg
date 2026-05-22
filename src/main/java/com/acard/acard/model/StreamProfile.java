package com.acard.acard.model;

/**
 * 流媒体预设的质量档位。
 */
public enum StreamProfile {
    STANDARD,
    HIGH;

    /**
     * 将字符串（如 "standard"、"high"）映射为枚举值。
     */
    public static StreamProfile fromString(String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "standard":
                return STANDARD;
            case "high":
                return HIGH;
            default:
                return null;
        }
    }

    /**
     * 返回与 ThinRemoteConfig.type 对应的字符串（"standard" 或 "high"）。
     */
    public String toTypeString() {
        return this == HIGH ? "high" : "standard";
    }
}
#version 440

layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;

layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    float brightness;   // -1 ~ 1
    float contrast;     // 0 ~ 2
    float saturation;   // 0 ~ 2
    float hue;          // -1 ~ 1
    float gamma;        // 0.01 ~ 10
};

layout(binding = 1) uniform sampler2D source;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec4 tex = texture(source, qt_TexCoord0);
    vec3 color = tex.rgb;
    
    // 伽马校正
    color = pow(color, vec3(1.0 / gamma));
    
    // 亮度调整
    color = color + brightness;
    
    // 对比度调整
    color = (color - 0.5) * contrast + 0.5;
    
    // 饱和度和色调调整（HSV空间）
    vec3 hsv = rgb2hsv(color);
    hsv.x = fract(hsv.x + hue * 0.5);  // 色调偏移
    hsv.y = hsv.y * saturation;        // 饱和度
    color = hsv2rgb(hsv);
    
    // 限制范围
    color = clamp(color, 0.0, 1.0);
    
    fragColor = vec4(color, tex.a) * qt_Opacity;
}


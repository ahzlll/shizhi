package com.ahz.shizhi.health;

import java.util.List;

/**
 * 健康检查返回体。
 *
 * <p>这一版只是给人和脚本看的存活探针，字段刻意保持扁平。
 * 等统一响应结构落地后（期一第三个提交），它会作为 data 的内层结构返回。
 */
public record HealthResponse(
        String status,
        String app,
        String version,
        List<String> profiles,
        String time
) {
}

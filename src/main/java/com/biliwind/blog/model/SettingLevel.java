package com.biliwind.blog.model;

/**
 * 后台系统设置级别划分：
 * NORMAL - 简单/常规设置（如展示信息、外观样式、普通业务开关等），修改后立即生效，不需要验证期；
 * CRITICAL - 关键设置（如网络代理/真实IP解析、全文检索健康连通、安全防刷核心参数等，可能影响站点正常运行与微服务健康），修改后需进入锁定验证期，配合 Watchdog 进行健康监控并支持手动确认或异常回滚。
 */
public enum SettingLevel {
    NORMAL,
    CRITICAL;

    public static SettingLevel fromString(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return SettingLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}

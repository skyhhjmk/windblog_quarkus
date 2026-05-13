package com.biliwind.blog.context;

import com.biliwind.blog.common.constant.RegionConstant;
import jakarta.enterprise.context.RequestScoped;

/**
 * 请求上下文中的区域信息
 */
@RequestScoped
public class RegionContext {

    /**
     * 当前请求所属区域，默认为 global
     */
    private String currentRegion = RegionConstant.GLOBAL;

    public String getCurrentRegion() {
        return currentRegion;
    }

    public void setCurrentRegion(String currentRegion) {
        if (currentRegion != null && !currentRegion.isBlank()) {
            this.currentRegion = currentRegion;
        }
    }
}

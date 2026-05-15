package com.biliwind.blog.context;

import com.biliwind.blog.model.BlogRegion;
import jakarta.enterprise.context.RequestScoped;

/**
 * 请求上下文中的区域信息
 */
@RequestScoped
public class RegionContext {

    /**
     * 当前请求所属区域，默认为 global
     */
    private BlogRegion currentRegion = BlogRegion.GLOBAL;

    public BlogRegion getCurrentRegion() {
        return currentRegion;
    }

    public void setCurrentRegion(BlogRegion currentRegion) {
        if (currentRegion != null) {
            this.currentRegion = currentRegion;
        }
    }
}

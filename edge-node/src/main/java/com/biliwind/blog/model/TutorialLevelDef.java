package com.biliwind.blog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 教程文章的级别定义，用于灵活管理单篇文章可选的分级结构
 */
@RegisterForReflection
public class TutorialLevelDef {

    /**
     * 级别标识（如 0, 1, 2）
     */
    public Short level;

    /**
     * 级别名称（如 "正常内容", "逐步内容", "知识点"）
     */
    public String name;

    /**
     * UI 显示颜色或标识（可选，用于前端区分渲染）
     */
    public String color;

    public TutorialLevelDef() {
    }

    public Short getLevel() {
        return level;
    }

    public void setLevel(Short level) {
        this.level = level;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}

package com.biliwind.blog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * 教程块实体，用于存储多级别块结构编辑器的数据
 */
@RegisterForReflection
public class TutorialBlock {

    /**
     * 块类型，例如：p, code, img, heading 等
     */
    public String type;

    /**
     * 教程级别（支持自定义多级别），例如：
     * 0 = 正常内容 (Normal)
     * 1 = 逐步内容 (Step-by-step)
     * 2 = 知识点内容 (Knowledge Point)
     * 可以根据具体需求灵活扩展更多级别
     */
    public Short level;

    /**
     * 块数据，可能包含多语言内容或其他属性
     */
    public Map<String, Object> data;

    /**
     * 子块列表，用于嵌套结构（如列表项）
     */
    public List<TutorialBlock> children;

    public TutorialBlock() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Short getLevel() {
        return level;
    }

    public void setLevel(Short level) {
        this.level = level;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public List<TutorialBlock> getChildren() {
        return children;
    }

    public void setChildren(List<TutorialBlock> children) {
        this.children = children;
    }
}

package com.biliwind.blog.model;

/**
 * 文章状态
 */
public enum PostStatus {
    /**
     * 草稿
     */
    DRAFT((short) 0),
    /**
     * 已发布
     */
    PUBLISHED((short) 1),
    /**
     * 已归档
     */
    ARCHIVED((short) 2);

    private final short code;

    PostStatus(short code) {
        this.code = code;
    }

    public short getCode() {
        return code;
    }

}
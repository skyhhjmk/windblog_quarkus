package com.biliwind.blog.model;

/**
 * 文章状态枚举
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
    ARCHIVED((short) 2),

    /**
     * 用户已提交、等待管理员审核
     */
    PENDING_REVIEW((short) 3);

    private final short code;

    PostStatus(short code) {
        this.code = code;
    }

    /**
     * @param code 枚举值
     * @return 枚举对象
     */
    public static PostStatus fromCode(short code) {
        for (PostStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * @return 枚举值
     */
    public short getCode() {
        return code;
    }
}

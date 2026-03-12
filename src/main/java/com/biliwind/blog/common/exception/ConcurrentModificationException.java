package com.biliwind.blog.common.exception;

/**
 * 并发修改异常（乐观锁冲突）
 */
public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException() {
        super("数据已被其他操作修改，请稍后重试");
    }

    public ConcurrentModificationException(String message) {
        super(message);
    }
}

package com.biliwind.blog.common.helper;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * PJAX 助手类
 */
public class PjaxHelper { // 类名遵循Java规范，首字母大写
    /**
     * PJAX 请求头名称
     */
    public static final String PJAX_HEADER = "X-PJAX";
    /**
     * PJAX 容器请求头名称
     */
    public static final String PJAX_CONTAINER_HEADER = "X-PJAX-Container";
    /**
     * PJAX 容器默认名称
     */
    public static final String PJAX_CONTAINER_DEFAULT = "#pjax-container";
    /**
     * PJAX 容器默认 ID
     */
    public static final String PJAX_CONTAINER_DEFAULT_ID = "pjax-container";

    /**
     * 判断是否为 PJAX 请求
     *
     * @param httpHeaders HttpHeaders 对象
     * @return true=是PJAX请求，false=不是
     */
    public static boolean isPjaxRequest(HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return false;
        }

        // 获取 X-PJAX 请求头，判断是否为 "true"
        String pjaxHeaderValue = httpHeaders.getHeaderString(PJAX_HEADER);
        return "true".equalsIgnoreCase(pjaxHeaderValue);
    }

    public static boolean isPjaxRequest(MultivaluedMap<String, String> headers) {

        if (headers == null) {
            return false;
        }

        String pjaxHeaderValue = headers.getFirst(PJAX_HEADER);

        return "true".equalsIgnoreCase(pjaxHeaderValue);
    }


    /**
     * 扩展方法：获取 PJAX 容器名称
     *
     * @param httpHeaders HttpHeaders 对象
     * @return 容器名称，无则返回默认值
     */
    public static String getPjaxContainer(HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return PJAX_CONTAINER_DEFAULT;
        }

        String container = httpHeaders.getHeaderString(PJAX_CONTAINER_HEADER);
        return (container == null || container.trim().isEmpty())
                ? PJAX_CONTAINER_DEFAULT
                : container;
    }
}
package com.biliwind.blog.common.constant;

import java.util.List;

public final class RoleConstant {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";
    public static final String GUEST = "GUEST";
    public static final List<String> DEFAULT_ROLES = List.of(
            SUPER_ADMIN,
            ADMIN,
            USER,
            GUEST
    );

    private RoleConstant() {
    }
}

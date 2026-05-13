package com.biliwind.blog.context;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class AdminRequestContext {

    private Long userId;
    private String username;
    private String roleName;
    private Boolean isSuperAdmin;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public Boolean getIsSuperAdmin() {
        return isSuperAdmin;
    }

    public void setIsSuperAdmin(Boolean isSuperAdmin) {
        this.isSuperAdmin = isSuperAdmin;
    }

    public boolean isSuperAdmin() {
        return Boolean.TRUE.equals(isSuperAdmin);
    }
}

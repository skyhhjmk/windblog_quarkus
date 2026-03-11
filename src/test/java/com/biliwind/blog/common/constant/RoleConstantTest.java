package com.biliwind.blog.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleConstantTest {

    @Test
    void shouldHaveCorrectSuperAdminRole() {
        assertEquals("SUPER_ADMIN", RoleConstant.SUPER_ADMIN);
    }

    @Test
    void shouldHaveCorrectAdminRole() {
        assertEquals("ADMIN", RoleConstant.ADMIN);
    }

    @Test
    void shouldHaveCorrectUserRole() {
        assertEquals("USER", RoleConstant.USER);
    }

    @Test
    void shouldHaveCorrectGuestRole() {
        assertEquals("GUEST", RoleConstant.GUEST);
    }

    @Test
    void shouldHaveFourDefaultRoles() {
        assertEquals(4, RoleConstant.DEFAULT_ROLES.size());
    }

    @Test
    void shouldContainSuperAdminInDefaultRoles() {
        assertTrue(RoleConstant.DEFAULT_ROLES.contains(RoleConstant.SUPER_ADMIN));
    }

    @Test
    void shouldContainAdminInDefaultRoles() {
        assertTrue(RoleConstant.DEFAULT_ROLES.contains(RoleConstant.ADMIN));
    }

    @Test
    void shouldContainUserInDefaultRoles() {
        assertTrue(RoleConstant.DEFAULT_ROLES.contains(RoleConstant.USER));
    }

    @Test
    void shouldContainGuestInDefaultRoles() {
        assertTrue(RoleConstant.DEFAULT_ROLES.contains(RoleConstant.GUEST));
    }

    @Test
    void shouldDefaultRolesBeUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> {
            RoleConstant.DEFAULT_ROLES.add("CUSTOM_ROLE");
        });
    }

    @Test
    void shouldHaveCorrectRoleOrder() {
        assertEquals(RoleConstant.SUPER_ADMIN, RoleConstant.DEFAULT_ROLES.get(0));
        assertEquals(RoleConstant.ADMIN, RoleConstant.DEFAULT_ROLES.get(1));
        assertEquals(RoleConstant.USER, RoleConstant.DEFAULT_ROLES.get(2));
        assertEquals(RoleConstant.GUEST, RoleConstant.DEFAULT_ROLES.get(3));
    }
}
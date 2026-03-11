package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.UploadRole;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UploadRoleService 单元测试
 * 测试上传角色服务
 */
@QuarkusTest
class UploadRoleServiceTest {

    @Inject
    UploadRoleService uploadRoleService;

    @Test
    void shouldListAllRoles() {
        List<UploadRole> roles = uploadRoleService.listRoles();
        assertNotNull(roles);
    }

    @Test
    void shouldFindRoleByName() {
        UploadRole role = uploadRoleService.findByName(RoleConstant.GUEST);
        if (role != null) {
            assertEquals(RoleConstant.GUEST, role.name);
        }
    }

    @Test
    void shouldReturnNullForNonExistentRole() {
        UploadRole role = uploadRoleService.findByName("NON_EXISTENT_ROLE_12345");
        assertNull(role);
    }

    @Test
    void shouldUpsertNewRole() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                List.of("image/png", "image/jpeg"),
                1024L,
                10240L
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE", request);

        assertNotNull(role);
        assertEquals("Test Role", role.displayName);
        assertEquals("Test Description", role.description);
        assertTrue(role.canUpload);
        assertEquals(2, role.allowedMimeTypes.size());
        assertTrue(role.allowedMimeTypes.contains("image/png"));
        assertTrue(role.allowedMimeTypes.contains("image/jpeg"));
        assertEquals(1024L, role.maxSingleUploadBytes);
        assertEquals(10240L, role.maxTotalUploadBytes);

        uploadRoleService.delete("TEST_ROLE");
    }

    @Test
    void shouldNormalizeMimeTypes() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                List.of(" IMAGE/PNG ", " image/jpeg ", "IMAGE/GIF"),
                null,
                null
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE_MIME", request);

        assertNotNull(role.allowedMimeTypes);
        assertEquals(3, role.allowedMimeTypes.size());
        assertTrue(role.allowedMimeTypes.contains("image/png"));
        assertTrue(role.allowedMimeTypes.contains("image/jpeg"));
        assertTrue(role.allowedMimeTypes.contains("image/gif"));

        uploadRoleService.delete("TEST_ROLE_MIME");
    }

    @Test
    void shouldRemoveDuplicateMimeTypes() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                List.of("image/png", "IMAGE/PNG", "image/jpeg"),
                null,
                null
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE_DUP", request);

        assertNotNull(role.allowedMimeTypes);
        assertEquals(2, role.allowedMimeTypes.size());

        uploadRoleService.delete("TEST_ROLE_DUP");
    }

    @Test
    void shouldHandleNullMimeTypes() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                null,
                null,
                null
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE_NULL", request);

        assertNotNull(role.allowedMimeTypes);
        assertTrue(role.allowedMimeTypes.isEmpty());

        uploadRoleService.delete("TEST_ROLE_NULL");
    }

    @Test
    void shouldHandleEmptyMimeTypes() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                List.of(),
                null,
                null
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE_EMPTY", request);

        assertNotNull(role.allowedMimeTypes);
        assertTrue(role.allowedMimeTypes.isEmpty());

        uploadRoleService.delete("TEST_ROLE_EMPTY");
    }

    @Test
    void shouldFilterBlankMimeTypes() {
        var request = new UploadRoleService.UploadRoleUpdateRequest(
                "Test Role",
                "Test Description",
                true,
                List.of("image/png", "  ", "", "image/jpeg"),
                null,
                null
        );

        UploadRole role = uploadRoleService.upsert("TEST_ROLE_BLANK", request);

        assertNotNull(role.allowedMimeTypes);
        assertEquals(2, role.allowedMimeTypes.size());

        uploadRoleService.delete("TEST_ROLE_BLANK");
    }

    @Test
    void shouldNotFailWhenDeletingNonExistentRole() {
        assertDoesNotThrow(() -> uploadRoleService.delete("NON_EXISTENT_ROLE_12345"));
    }
}
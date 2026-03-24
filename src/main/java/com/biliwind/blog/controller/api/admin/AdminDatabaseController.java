package com.biliwind.blog.controller.api.admin;

import io.agroal.api.AgroalDataSource;
import io.quarkus.liquibase.LiquibaseFactory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.resource.ResourceAccessor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/database")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AdminDatabase")
public class AdminDatabaseController {

    @Inject
    LiquibaseFactory liquibaseFactory;

    @Inject
    AgroalDataSource dataSource;

    @POST
    @Path("/migrate")
    @RolesAllowed("admin")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "手动触发数据库迁移")
    @APIResponse(responseCode = "200", description = "迁移成功")
    public Response migrate() {
        try {
            Liquibase liquibase = liquibaseFactory.createLiquibase();
            liquibase.update();
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "数据库迁移成功完成");
            
            return Response.ok(result).build();
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "数据库迁移失败：" + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    @POST
    @Path("/seed")
    @RolesAllowed("admin")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "一键添加数据库种子数据")
    @APIResponse(responseCode = "200", description = "种子数据添加成功")
    public Response seed() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            
            try {
                seedInitialData(connection);
                connection.commit();
                
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("message", "数据库种子数据添加成功");
                
                return Response.ok(result).build();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "数据库种子数据添加失败：" + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    private void seedInitialData(Connection connection) throws SQLException {
        String checkCategories = "SELECT COUNT(*) FROM categories";
        String checkTags = "SELECT COUNT(*) FROM tags";
        String checkLinks = "SELECT COUNT(*) FROM links";
        
        try (var stmt = connection.createStatement()) {
            boolean needsSeed = true;
            
            try (var rs = stmt.executeQuery(checkCategories)) {
                if (rs.next() && rs.getInt(1) > 0) {
                    needsSeed = false;
                }
            }
            
            if (needsSeed) {
                stmt.execute("INSERT INTO categories (name, slug, description, parent_id, path, created_at, updated_at) " +
                           "VALUES ('技术', 'tech', '技术类文章', NULL, 'tech', NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
                
                stmt.execute("INSERT INTO categories (name, slug, description, parent_id, path, created_at, updated_at) " +
                           "VALUES ('生活', 'life', '生活类文章', NULL, 'life', NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
                
                stmt.execute("INSERT INTO tags (name, slug, created_at, updated_at) " +
                           "VALUES ('Java', 'java', NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
                
                stmt.execute("INSERT INTO tags (name, slug, created_at, updated_at) " +
                           "VALUES ('Quarkus', 'quarkus', NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
                
                stmt.execute("INSERT INTO tags (name, slug, created_at, updated_at) " +
                           "VALUES ('教程', 'tutorial', NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
                
                stmt.execute("INSERT INTO links (name, url, description, logo, status, type, created_at, updated_at) " +
                           "VALUES ('WindBlog GitHub', 'https://github.com/windblog', 'WindBlog 开源项目', NULL, 1, 0, NOW(), NOW()) " +
                           "ON CONFLICT DO NOTHING");
            }
        }
    }
}

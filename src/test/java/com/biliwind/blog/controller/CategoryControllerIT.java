package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * CategoryController 集成测试
 * 测试分类相关端点
 */
@QuarkusIntegrationTest
class CategoryControllerIT {

    @Test
    void shouldReturnCategoryListPage() {
        given()
                .when().get("/category")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnCategoryListWithPagination() {
        given()
                .when().get("/category?page=1")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnCategoryListWithSearch() {
        given()
                .when().get("/category?q=test")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestForCategoryList() {
        given()
                .header("X-PJAX", "true")
                .when().get("/category")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnNotFoundForNonExistentCategory() {
        given()
                .when().get("/category/non-existent-category-12345")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandleCategoryRequestWithHtmlExtension() {
        given()
                .when().get("/category/non-existent.html")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandlePjaxRequestForCategoryDetail() {
        given()
                .header("X-PJAX", "true")
                .when().get("/category/non-existent-category")
                .then()
                .statusCode(404);
    }
}

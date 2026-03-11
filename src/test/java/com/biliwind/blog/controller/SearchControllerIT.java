package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * SearchController 集成测试
 * 测试搜索相关端点
 */
@QuarkusIntegrationTest
class SearchControllerIT {

    @Test
    void shouldReturnSearchPage() {
        given()
                .when().get("/search")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithKeyword() {
        given()
                .when().get("/search?q=test")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithPostType() {
        given()
                .when().get("/search?q=test&type=post")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithTagType() {
        given()
                .when().get("/search?q=test&type=tag")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithCategoryType() {
        given()
                .when().get("/search?q=test&type=category")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithInvalidType() {
        given()
                .when().get("/search?q=test&type=invalid")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithLatestSort() {
        given()
                .when().get("/search?q=test&sort=latest")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithHotSort() {
        given()
                .when().get("/search?q=test&sort=hot")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithInvalidSort() {
        given()
                .when().get("/search?q=test&sort=invalid")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWith7DaysDateFilter() {
        given()
                .when().get("/search?q=test&date=7d")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWith30DaysDateFilter() {
        given()
                .when().get("/search?q=test&date=30d")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWith365DaysDateFilter() {
        given()
                .when().get("/search?q=test&date=365d")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithInvalidDateFilter() {
        given()
                .when().get("/search?q=test&date=invalid")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithPagination() {
        given()
                .when().get("/search?q=test&page=1")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithLargePageNumber() {
        given()
                .when().get("/search?q=test&page=999")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestForSearch() {
        given()
                .header("X-PJAX", "true")
                .when().get("/search?q=test")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestWithContainer() {
        given()
                .header("X-PJAX", "true")
                .header("X-PJAX-Container", "#custom-container")
                .when().get("/search?q=test")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithAllFilters() {
        given()
                .when().get("/search?q=test&type=post&sort=latest&date=7d&page=1")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithEmptyKeyword() {
        given()
                .when().get("/search?q=")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnSearchPageWithSpecialCharactersInKeyword() {
        given()
                .when().get("/search?q=test%20keyword")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

}
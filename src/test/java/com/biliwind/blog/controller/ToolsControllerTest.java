package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class ToolsControllerTest {

    @Test
    void shouldReturnToolsIndexPage() {
        given()
                .when().get("/tools")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("常用在线工具箱"))
                .body(containsString("新一代智能子网掩码计算器"));
    }

    @Test
    void shouldReturnSubnetCalculatorPage() {
        given()
                .when().get("/tools/subnet")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("新一代智能子网掩码推演计算器"))
                .body(containsString("32-Bit 二进制位图可视化"))
                .body(containsString("subnet-calculator.js"));
    }

    @Test
    void shouldSupportPjaxHeader() {
        given()
                .header("X-PJAX", "true")
                .when().get("/tools/subnet")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("id=\"pjax-content-root\""))
                .body(containsString("新一代智能子网掩码推演计算器"));
    }
}

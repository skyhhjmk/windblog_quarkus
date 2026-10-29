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
                .body(containsString("Developer toolbox"))
                .body(containsString("Smart subnet calculator"));

        given()
                .when().get("/zh-cn/tools")
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
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnTimestampPage() {
        given()
                .when().get("/tools/timestamp")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("Unix 时间戳"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnLogFormatterPage() {
        given()
                .when().get("/tools/log-formatter")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("日志格式化"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnBaseEncoderPage() {
        given()
                .when().get("/tools/base-encoder")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("Base64"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnCronPage() {
        given()
                .when().get("/tools/cron")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("Cron 表达式生成与执行时间模拟器"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnRegexPage() {
        given()
                .when().get("/tools/regex")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("正则表达式在线测试与速查工具"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnHashPage() {
        given()
                .when().get("/tools/hash")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("现代哈希计算与本地文件校验器"))
                .body(containsString("tools.js"));
    }

    @Test
    void shouldReturnJsonDiffPage() {
        given()
                .when().get("/tools/json-diff")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("JSON 在线格式化与可视化差分对比"))
                .body(containsString("tools.js"));
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

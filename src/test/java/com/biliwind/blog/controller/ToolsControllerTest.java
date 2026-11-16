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
    void shouldReturnWebSerialPage() {
        given()
                .when().get("/tools/serial")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("Web Serial 串口终端"))
                .body(containsString("serialToolApp"))
                .body(containsString("tools.js"));

        given()
                .when().get("/assets/js/tools.js")
                .then()
                .statusCode(200)
                .body(containsString("openPortWithRecovery"))
                .body(containsString("forceClosePort"))
                .body(containsString("port.forget()"));
    }

    @Test
    void shouldReturnFileTransferPage() {
        given()
                .when().get("/tools/file-transfer")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("免服务器 P2P 文件快传"))
                .body(containsString("fileTransferToolApp"))
                .body(containsString("ftE2eeToggle"))
                .body(containsString("ftEccSelect"))
                .body(containsString("tools.js"));

        given()
                .when().get("/assets/js/file-transfer.js")
                .then()
                .statusCode(200)
                .body(containsString("WindBlogFileTransfer"))
                .body(containsString("rsReconstruct"));

        given()
                .when().get("/assets/vendor/file-transfer-wasm/file_transfer_ecc.wasm")
                .then()
                .statusCode(200);
    }

    @Test
    void shouldReturnImageGeneratorPageAndFullRowOrderInToolbox() {
        String indexHtml = given()
                .when().get("/zh-cn/tools")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("程序化图像生成与全能图像处理工作台"))
                .extract().asString();

        int ftIndex = indexHtml.indexOf("data-tool-id=\"file-transfer\"");
        int igIndex = indexHtml.indexOf("data-tool-id=\"image-generator\"");
        int subnetIndex = indexHtml.indexOf("data-tool-id=\"subnet-calculator\"");
        org.junit.jupiter.api.Assertions.assertTrue(ftIndex >= 0 && igIndex > ftIndex && subnetIndex > igIndex,
                "image-generator should be positioned immediately below file-transfer and before subnet-calculator");

        given()
                .when().get("/tools/image-generator")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("程序化图像生成与全能图像处理工作台"))
                .body(containsString("imageGeneratorToolApp"))
                .body(containsString("domain-warping"))
                .body(containsString("floyd-steinberg"))
                .body(containsString("image/x-icon"))
                .body(containsString("tools.js"));

        given()
                .when().get("/assets/js/image-generator.js")
                .then()
                .statusCode(200)
                .body(containsString("WindBlogImageGenerator"))
                .body(containsString("encodeCanvasToIcoBlob"))
                .body(containsString("encodeCanvasToBmpBlob"));
    }

    @Test
    void shouldReturnImageConverterPageWithRichSeo() {
        given()
                .when().get("/tools/image-converter")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8")
                .body(containsString("免费极速本地图像格式转换器"))
                .body(containsString("imageConverterToolApp"))
                .body(containsString("WebP 转 PNG"))
                .body(containsString("AVIF 转 PNG"))
                .body(containsString("Favicon"))
                .body(containsString("tools.js"));

        given()
                .when().get("/assets/js/image-converter.js")
                .then()
                .statusCode(200)
                .body(containsString("WindBlogImageConverter"))
                .body(containsString("encodeCanvasToIcoBlob"))
                .body(containsString("encodeCanvasToBmpBlob"));
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

    @Test
    void shouldReturnNewToolboxPagesAndBilingualSeo() {
        given().when().get("/tools/ai-token").then().statusCode(200)
                .body(containsString("aiTokenToolApp"))
                .body(containsString("application/ld+json"))
                .body(containsString("WebApplication"));
        given().when().get("/tools/jwt").then().statusCode(200)
                .body(containsString("jwtToolApp"));
        given().when().get("/tools/config-converter").then().statusCode(200)
                .body(containsString("configConverterToolApp"));
        given().when().get("/tools/curl-converter").then().statusCode(200)
                .body(containsString("curlConverterToolApp"));
        given().when().get("/tools/id-generator").then().statusCode(200)
                .body(containsString("idGeneratorToolApp"));
        given().when().get("/tools/jsonl-to-table").then().statusCode(200)
                .body(containsString("jsonlTableToolApp"));

        // Bilingual SEO title check
        given().when().get("/en-us/tools/jsonl-to-table").then().statusCode(200)
                .body(containsString("JSONL (NDJSON) to Table Viewer"));
        given().when().get("/assets/js/tools-suite.js").then().statusCode(200)
                .body(containsString("WindBlogToolsSuite"));
    }
}

package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LanguageRoutingTest {

    @Test
    void globalRegionDefaultsToEnglish() {
        given()
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .when().get("/")
                .then().statusCode(200)
                .body(containsString("<html lang=\"en-US\">"))
                .body(containsString("Latest articles"));
    }

    @Test
    void languagePrefixSelectsChinese() {
        given()
                .when().get("/zh-cn/")
                .then().statusCode(200)
                .body(containsString("<html lang=\"zh-cn\">"))
                .body(containsString("最近文章"));
    }

    @Test
    void languagePrefixWorksForEveryPublicSection() {
        for (String path : new String[]{"/en-US/tag", "/en-US/category", "/en-US/tools",
                "/zh-cn/tag", "/zh-cn/tools", "/zh-cn/search", "/zh-cn/link",
                "/zh-cn/user/login"}) {
            given().when().get(path).then().statusCode(200);
        }
    }

    @Test
    void languageQueryParameterOverridesRegionDefault() {
        given()
                .when().get("/?lang=zh-cn")
                .then().statusCode(200)
                .body(containsString("<html lang=\"zh-cn\">"))
                .cookie("user_lang_v2", "zh-cn");
    }

    @Test
    void prefixedUnknownPostStillReturns404() {
        given().when().get("/en-US/post/nonexistent-post").then().statusCode(404);
        given().when().get("/zh-cn/post/nonexistent-post").then().statusCode(404);
    }

    @Test
    void switcherLinksPreserveThePage() {
        given()
                .when().get("/zh-cn/tag")
                .then().statusCode(200)
                .body(containsString("href=\"/tag\""))
                .body(containsString("href=\"/zh-cn/tag\""));
    }
}

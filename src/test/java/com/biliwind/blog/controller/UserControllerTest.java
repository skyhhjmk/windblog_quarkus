package com.biliwind.blog.controller;

import com.biliwind.blog.service.security.ProofOfWorkService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class UserControllerTest {

    @Test
    void shouldFallbackExternalRedirectToHome() {
        given()
                .queryParam("redirect", "https://evil.example")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/\""));
    }

    @Test
    void shouldKeepInternalRedirectPath() {
        given()
                .queryParam("redirect", "/posts/demo")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/posts/demo\""));
    }

    @Test
    void shouldFallbackProtocolRelativeRedirectToHome() {
        given()
                .queryParam("redirect", "//evil.example")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/\""));
    }

    @Test
    void shouldRenderPasswordRecoveryPage() {
        given()
                .when()
                .get("/user/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("找回密码"))
                .body(containsString("forgotPasswordForm"));
    }

    @Test
    void shouldRenderExpiredPasswordResetPageWithoutToken() {
        given()
                .when()
                .get("/user/reset-password")
                .then()
                .statusCode(200)
                .body(containsString("Invalid or expired link"));

        given()
                .when()
                .get("/zh-cn/user/reset-password")
                .then()
                .statusCode(200)
                .body(containsString("链接无效或已过期"));
    }

    @Test
    void shouldRenderLegalPages() {
        given()
                .when()
                .get("/terms")
                .then()
                .statusCode(200)
                .body(containsString("用户协议"));

        given()
                .when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("隐私政策"));
    }

    @Test
    void shouldIssueScopedPowChallengesForLoginAndRegister() {
        given()
                .queryParam("action", "login")
                .when()
                .get("/user/api/pow/challenge")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", startsWith("v2.login."))
                .body("data.difficulty", greaterThanOrEqualTo(ProofOfWorkService.MIN_DIFFICULTY));

        given()
                .queryParam("action", "register")
                .when()
                .get("/user/api/pow/challenge")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("data.id", startsWith("v2.register."))
                .body("data.difficulty", greaterThanOrEqualTo(ProofOfWorkService.MIN_DIFFICULTY));

        given()
                .queryParam("action", "invalid")
                .when()
                .get("/user/api/pow/challenge")
                .then()
                .statusCode(400)
                .body("success", equalTo(false));
    }

    @Test
    void shouldRejectLoginAndRegisterWithoutValidPow() {
        Response bootstrap = given().get("/user/login");
        String csrfToken = bootstrap.getCookie("XSRF-TOKEN");

        given()
                .cookie("XSRF-TOKEN", csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .formParam("account", "nonexistent")
                .formParam("password", "secret123")
                .when()
                .post("/user/api/login")
                .then()
                .statusCode(400)
                .body("success", equalTo(false));

        Response loginPow = given()
                .queryParam("action", "login")
                .get("/user/api/pow/challenge");
        String loginChallengeId = loginPow.jsonPath().getString("data.id");
        int loginDifficulty = loginPow.jsonPath().getInt("data.difficulty");
        String loginNonce = solvePow(loginChallengeId, loginDifficulty);

        // Using a login PoW challenge on the register endpoint must be rejected.
        given()
                .cookie("XSRF-TOKEN", csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .formParam("username", "pow_test_user")
                .formParam("email", "pow_test@example.com")
                .formParam("password", "secret123")
                .formParam("confirmPassword", "secret123")
                .formParam("agreement", "on")
                .formParam("challengeId", loginChallengeId)
                .formParam("powNonce", loginNonce)
                .when()
                .post("/user/api/register")
                .then()
                .statusCode(400)
                .body("success", equalTo(false));
    }

    private static String solvePow(String id, int difficulty) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (long nonce = 0; ; nonce++) {
                String proof = Long.toString(nonce);
                byte[] digest = sha.digest((id + ":" + proof).getBytes(StandardCharsets.UTF_8));
                if (ProofOfWorkService.hasLeadingZeroBits(digest, difficulty)) {
                    return proof;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

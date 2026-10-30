package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class UserRepostLicenseControllerTest {

    @Inject
    PasswordHasher passwordHasher;

    @Test
    void shouldListOwnLicensesAndBlockBannedUsersFromApplying() {
        String username = "repost-test-" + System.nanoTime();
        String email = username + "@example.com";
        String password = "repost-test-password";
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                User user = new User();
                user.username = username;
                user.email = email;
                user.password = passwordHasher.hash(password);
                user.status = 1;
                user.roleName = "USER";
                user.subscribeArticleUpdates = false;
                user.subscribePromotions = false;
                user.persist();
            });

            Response bootstrap = given().get("/user/login");
            bootstrap.then().statusCode(200);
            String csrfToken = bootstrap.getCookie("XSRF-TOKEN");

            Response login = given()
                    .cookie("XSRF-TOKEN", csrfToken)
                    .header("X-XSRF-TOKEN", csrfToken)
                    .formParam("account", username)
                    .formParam("password", password)
                    .post("/user/api/login");
            login.then().statusCode(200);

            String userToken = login.getCookie("user_token");

            given()
                    .cookie("user_token", userToken)
                    .get("/user/center")
                    .then()
                    .statusCode(200)
                    .body(containsString("data-user-tab=\"repost\""))
                    .body(containsString("repostLicenseList"));

            given()
                    .cookie("user_token", userToken)
                    .get("/api/user/repost/licenses")
                    .then()
                    .statusCode(200)
                    .body("success", equalTo(true))
                    .body("data.repostBanned", equalTo(false))
                    .body("data.items.size()", equalTo(0));

            QuarkusTransaction.requiringNew().run(() -> {
                User user = User.find("username", username).firstResult();
                user.repostBanned = true;
                user.repostBanReason = "test ban";
            });

            given()
                    .cookie("user_token", userToken)
                    .cookie("XSRF-TOKEN", csrfToken)
                    .header("X-XSRF-TOKEN", csrfToken)
                    .contentType("application/json")
                    .body("{\"postSlug\":\"missing-post\",\"targetUrl\":\"https://repost.example/demo\"}")
                    .post("/api/user/repost/licenses")
                    .then()
                    .statusCode(403)
                    .body("success", equalTo(false))
                    .body("banned", equalTo(true));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> User.delete("username", username));
        }
    }
}

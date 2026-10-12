package com.biliwind.blog.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.security.SecureRandom;
import java.util.UUID;

/** Short-lived, single-use arithmetic challenges shared across application instances. */
@ApplicationScoped
public class CommentCaptchaService {
    private static final String PREFIX = "windblog:comment-captcha:";
    private final SecureRandom random = new SecureRandom();

    @Inject
    RedisDataSource redis;

    public Challenge issue() {
        int left = random.nextInt(9) + 1;
        int right = random.nextInt(9) + 1;
        String id = UUID.randomUUID().toString();
        try {
            redis.value(String.class).set(PREFIX + id, Integer.toString(left + right), new SetArgs().ex(300));
        } catch (Exception e) {
            throw new ServiceUnavailableException("验证码暂时不可用，请稍后重试");
        }
        return new Challenge(id, left + " + " + right + " = ?");
    }

    public void verify(String id, String answer) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BadRequestException("请先获取验证码");
        }
        String expected;
        try {
            expected = redis.value(String.class).getdel(PREFIX + id);
        } catch (Exception e) {
            throw new ServiceUnavailableException("验证码暂时不可用，请稍后重试");
        }
        if (expected == null || answer == null || !expected.equals(answer.trim())) {
            throw new BadRequestException("验证码错误或已过期，请重新输入");
        }
    }

    @RegisterForReflection
    public record Challenge(String id, String question) {}
}

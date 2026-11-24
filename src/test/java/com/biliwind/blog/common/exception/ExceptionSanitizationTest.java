package com.biliwind.blog.common.exception;

import com.biliwind.blog.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionSanitizationTest {

    @Test
    void sanitizesFrameworkWebExceptionsWhileKeepingWbErrorCode() {
        GlobalExceptionMapper mapper = new GlobalExceptionMapper();

        Response r415 = mapper.toResponse(new NotSupportedException(
                "The content-type header value did not match the value in @Consumes"));
        assertEquals(415, r415.getStatus());
        ErrorResponse body415 = (ErrorResponse) r415.getEntity();
        assertNotNull(body415);
        assertFalse(body415.message().contains("@Consumes"));
        assertTrue(body415.message().contains("不支持的请求内容类型"));
        assertEquals("WB-HTTP-OTHER-001", body415.errorCode());

        Response r404 = mapper.toResponse(new NotFoundException(
                "Unable to find matching target resource method"));
        assertEquals(404, r404.getStatus());
        ErrorResponse body404 = (ErrorResponse) r404.getEntity();
        assertFalse(body404.message().contains("target resource method"));
        assertTrue(body404.message().contains("请求的资源不存在"));
        assertEquals("WB-HTTP-404-001", body404.errorCode());

        Response r405 = mapper.toResponse(new NotAllowedException("GET"));
        assertEquals(405, r405.getStatus());
        ErrorResponse body405 = (ErrorResponse) r405.getEntity();
        assertTrue(body405.message().contains("不支持的请求方法"));
    }

    @Test
    void stripsInternalMethodPrefixFromConstraintViolations() {
        ConstraintViolationExceptionMapper mapper = new ConstraintViolationExceptionMapper();
        Path path = pathOf("login", "account");
        ConstraintViolation<?> violation = violation(path, "不能为空");

        Response response = mapper.toResponse(new ConstraintViolationException(Set.of(violation)));

        assertEquals(400, response.getStatus());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertNotNull(body);
        assertEquals("ValidationError", body.errorType());
        assertEquals("WB-HTTP-400-001", body.errorCode());
        assertTrue(body.message().contains("account 不能为空"));
        assertFalse(body.message().contains("login.account"));
    }

    private static Path pathOf(String... segments) {
        List<Path.Node> nodes = java.util.Arrays.stream(segments)
                .map(name -> (Path.Node) Proxy.newProxyInstance(
                        Path.Node.class.getClassLoader(),
                        new Class<?>[]{Path.Node.class},
                        (proxy, method, args) -> "getName".equals(method.getName()) ? name : null))
                .toList();
        return (Path) Proxy.newProxyInstance(
                Path.class.getClassLoader(),
                new Class<?>[]{Path.class},
                (proxy, method, args) -> "iterator".equals(method.getName()) ? nodes.iterator() : null);
    }

    @SuppressWarnings("unchecked")
    private static ConstraintViolation<?> violation(Path path, String message) {
        return (ConstraintViolation<?>) Proxy.newProxyInstance(
                ConstraintViolation.class.getClassLoader(),
                new Class<?>[]{ConstraintViolation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPropertyPath" -> path;
                    case "getMessage" -> message;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Violation(" + message + ")";
                    default -> null;
                });
    }
}

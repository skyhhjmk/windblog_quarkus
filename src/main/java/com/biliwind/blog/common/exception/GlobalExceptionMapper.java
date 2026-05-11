package com.biliwind.blog.common.exception;

import com.biliwind.blog.common.dto.ErrorResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局异常映射器，将所有未捕获的异常转化为统一的 JSON 格式。
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        // 优先处理自定义业务异常，确保更具体的异常类型优先匹配
        if (exception instanceof ConflictException) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(exception.getMessage(), "ConflictError"))
                    .build();
        }

        if (exception instanceof ConcurrentModificationException) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(exception.getMessage(), "ConcurrentUpdateError"))
                    .build();
        }

        if (exception instanceof BadRequestException) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(exception.getMessage(), "BadRequestError"))
                    .build();
        }

        if (exception instanceof jakarta.validation.ConstraintViolationException) {
            jakarta.validation.ConstraintViolationException violationEx = (jakarta.validation.ConstraintViolationException) exception;
            String message = violationEx.getMessage();
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(message, "ValidationError"))
                    .build();
        }

        // 处理 WebApplicationException
        if (exception instanceof WebApplicationException) {
            WebApplicationException webEx = (WebApplicationException) exception;
            Response response = webEx.getResponse();

            String message = exception.getMessage();
            if (message == null || message.isEmpty()) {
                message = "请求处理失败";
            }

            return Response.fromResponse(response)
                    .entity(new ErrorResponse(message, "WebException"))
                    .build();
        }

        // 默认处理 (500)
        LOGGER.error("未捕获的全局异常: ", exception);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("服务器内部错误，请联系管理员", "InternalServerError"))
                .build();
    }
}

package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.BatchTranslateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostResult;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.SubmitTranslationTasksRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationBatchView;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationPage;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationTaskView;
import com.biliwind.blog.service.AdminTranslationService;
import com.biliwind.blog.service.TranslationTaskService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


/**
 * Translation management: a WordPress-like overview of which articles already
 * have a target-language version, plus one-click and batched translation.
 */
@Path("/api/admin/translations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminTranslation")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminTranslationController {

    @Inject
    AdminTranslationService translationService;

    @Inject
    TranslationTaskService taskService;

    @GET
    @Operation(summary = "分页查询文章译本状态")
    public TranslationPage list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("keyword") String keyword,
            @QueryParam("translationStatus") String translationStatus,
            @QueryParam("targetLanguage") String targetLanguage) {
        return translationService.list(status, keyword, translationStatus, targetLanguage, page, pageSize);
    }

    @POST
    @Path("/tasks")
    @Operation(summary = "异步提交翻译任务，由后台队列执行")
    public TranslationBatchView submitTasks(SubmitTranslationTasksRequest request) {
        if (request == null || request.postIds() == null || request.postIds().isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        return taskService.submit(request.postIds(), request.targetLanguage(),
                request.sourceLanguage(), Boolean.TRUE.equals(request.overwrite()));
    }

    @GET
    @Path("/tasks")
    @Operation(summary = "查询翻译任务批次进度")
    public TranslationBatchView taskBatch(@QueryParam("batchId") String batchId) {
        return taskService.batchView(batchId);
    }

    @GET
    @Path("/tasks/{taskId}")
    @Operation(summary = "查询单个翻译任务")
    public TranslationTaskView task(@PathParam("taskId") Long taskId) {
        return taskService.view(taskId);
    }

    @POST
    @Path("/{id}/translate")
    @Blocking
    @Operation(summary = "一键将文章翻译为目标语言并保存")
    public TranslatePostResult translate(@PathParam("id") Long id, TranslatePostRequest request) {
        if (request == null) {
            throw new BadRequestException("翻译请求不能为空");
        }
        return translationService.translatePost(id,
                request.targetLanguage(),
                request.sourceLanguage(),
                Boolean.TRUE.equals(request.overwrite()));
    }

    @POST
    @Path("/batch-translate")
    @Blocking
    @Operation(summary = "批量提交异步翻译任务")
    public TranslationBatchView batchTranslate(BatchTranslateRequest request) {
        if (request == null || request.postIds() == null || request.postIds().isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        return taskService.submit(request.postIds(), request.targetLanguage(),
                request.sourceLanguage(), Boolean.TRUE.equals(request.overwrite()));
    }
}

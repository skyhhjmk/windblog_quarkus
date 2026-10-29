package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.BatchTranslateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.BatchTranslateResult;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslatePostResult;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.TranslationPage;
import com.biliwind.blog.service.AdminTranslationService;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    private static final int MAX_BATCH_SIZE = 20;

    @Inject
    AdminTranslationService translationService;

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
    @Operation(summary = "批量翻译所选文章")
    public BatchTranslateResult batchTranslate(BatchTranslateRequest request) {
        if (request == null || request.postIds() == null || request.postIds().isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : request.postIds()) {
            if (id != null && id > 0) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BadRequestException("请选择需要翻译的文章");
        }
        if (uniqueIds.size() > MAX_BATCH_SIZE) {
            throw new BadRequestException("单次最多翻译 " + MAX_BATCH_SIZE + " 篇文章");
        }
        String target = request.targetLanguage();
        boolean overwrite = Boolean.TRUE.equals(request.overwrite());
        List<TranslatePostResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int translated = 0;
        int skipped = 0;
        for (Long id : uniqueIds) {
            try {
                TranslatePostResult result = translationService.translatePost(id, target,
                        request.sourceLanguage(), overwrite);
                results.add(result);
                if (result.skipped()) {
                    skipped++;
                } else {
                    translated++;
                }
            } catch (RuntimeException exception) {
                errors.add("文章 #" + id + ": " + message(exception));
            }
        }
        return new BatchTranslateResult(uniqueIds.size(), translated, skipped,
                errors.size(), results, errors);
    }

    private String message(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}

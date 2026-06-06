package com.biliwind.blog.service;

import com.biliwind.blog.model.ImageProcessingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);

    @ConfigProperty(name = "media.upload.dir", defaultValue = "uploads")
    String mediaUploadDir;

    @Inject
    ExternalToolResolver externalToolResolver;

    public Path extractCoverFrame(Path videoFilePath, Path outputCoverPath) throws IOException {
        String ffmpegPath = getConfigValue("ffmpeg_path", "/usr/bin/ffmpeg");

        ExternalToolResolver.ToolResolution ffmpegResolution = resolveFfmpeg(ffmpegPath);
        externalToolResolver.requireAvailable(ffmpegResolution, "FFmpeg");

        Path uploadRootPath = Path.of(mediaUploadDir).toAbsolutePath().normalize();
        Path normalizedVideoFilePath = videoFilePath.toAbsolutePath().normalize();
        Path normalizedOutputCoverPath = outputCoverPath.toAbsolutePath().normalize();
        if (!normalizedVideoFilePath.startsWith(uploadRootPath)) {
            throw new IOException("视频文件必须位于媒体上传目录内");
        }
        if (!normalizedOutputCoverPath.startsWith(uploadRootPath)) {
            throw new IOException("封面文件必须位于媒体上传目录内");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegResolution.resolvedPath(),
                "-ss",
                "3",
                "-i",
                normalizedVideoFilePath.toString(),
                "-frames:v",
                "1",
                "-q:v",
                "2",
                normalizedOutputCoverPath.toString()
        );
        Process process = processBuilder.start();

        try {
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                throw new IOException("FFmpeg 封面提取超时(60s)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg 封面提取被中断", e);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes());
            throw new IOException("FFmpeg 封面提取失败, 退出码: " + exitCode + ", 错误: " + stderr);
        }

        if (!Files.exists(normalizedOutputCoverPath)) {
            throw new IOException("FFmpeg 封面提取完成但输出文件不存在: " + normalizedOutputCoverPath);
        }

        long fileSize = Files.size(normalizedOutputCoverPath);
        if (fileSize <= 0) {
            throw new IOException("FFmpeg 封面提取完成但输出文件大小为0: " + normalizedOutputCoverPath);
        }

        return normalizedOutputCoverPath;
    }

    private String getConfigValue(String key, String defaultValue) {
        ImageProcessingConfig config = ImageProcessingConfig.find("configKey", key).firstResult();
        if (config != null) {
            return config.configValue;
        }
        return defaultValue;
    }

    public ExternalToolResolver.ToolResolution resolveFfmpeg(String configuredPath) {
        return externalToolResolver.resolveTool(
                configuredPath,
                "/usr/bin/ffmpeg",
                "ffmpeg.exe",
                List.of(
                        "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                        "C:\\ffmpeg\\bin\\ffmpeg.exe"
                )
        );
    }
}

package com.biliwind.blog.service;

import com.biliwind.blog.model.ImageProcessingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingService.class);

    public Path extractCoverFrame(Path videoFilePath, Path outputCoverPath) throws IOException {
        String ffmpegPath = getConfigValue("ffmpeg_path", "/usr/bin/ffmpeg");

        Path ffmpegPathObj = Path.of(ffmpegPath);
        if (!Files.exists(ffmpegPathObj) || !Files.isExecutable(ffmpegPathObj)) {
            throw new IOException("FFmpeg 工具不可用: " + ffmpegPath);
        }

        String command = ffmpegPath + " -ss 3 -i " + videoFilePath.toString()
                + " -frames:v 1 -q:v 2 " + outputCoverPath.toString();

        Process process = Runtime.getRuntime().exec(command);

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

        if (!Files.exists(outputCoverPath)) {
            throw new IOException("FFmpeg 封面提取完成但输出文件不存在: " + outputCoverPath);
        }

        long fileSize = Files.size(outputCoverPath);
        if (fileSize <= 0) {
            throw new IOException("FFmpeg 封面提取完成但输出文件大小为0: " + outputCoverPath);
        }

        return outputCoverPath;
    }

    private String getConfigValue(String key, String defaultValue) {
        ImageProcessingConfig config = ImageProcessingConfig.find("configKey", key).firstResult();
        if (config != null) {
            return config.configValue;
        }
        return defaultValue;
    }
}

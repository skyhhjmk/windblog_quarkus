package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.ImageDimensionReader;
import com.biliwind.blog.model.ImageProcessingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class ImageProcessingService {

    @Inject
    ExternalToolResolver externalToolResolver;

    public Path convertToWebp(Path originalImagePath, Path outputWebpPath) throws IOException {
        String cwebpPath = getConfigValue("cwebp_path", "/usr/bin/cwebp");
        String qualityStr = getConfigValue("webp_quality", "0.82");
        String methodStr = getConfigValue("webp_method", "4");

        ExternalToolResolver.ToolResolution cwebpResolution = resolveCwebp(cwebpPath);
        externalToolResolver.requireAvailable(cwebpResolution, "cwebp");

        double quality = Double.parseDouble(qualityStr);
        int qualityPercent = (int) (quality * 100);

        int method = Integer.parseInt(methodStr);

        ProcessBuilder processBuilder = new ProcessBuilder(
                cwebpResolution.resolvedPath(),
                "-q",
                String.valueOf(qualityPercent),
                "-m",
                String.valueOf(method),
                "-mt",
                originalImagePath.toString(),
                "-o",
                outputWebpPath.toString()
        );
        Process process = processBuilder.start();

        try {
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                throw new IOException("cwebp 转换超时(30s)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cwebp 转换被中断", e);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes());
            throw new IOException("cwebp 转换失败, 退出码: " + exitCode + ", 错误: " + stderr);
        }

        if (!Files.exists(outputWebpPath)) {
            throw new IOException("cwebp 转换完成但输出文件不存在: " + outputWebpPath);
        }

        return outputWebpPath;
    }

    public Path generatePlaceholder(Path originalImagePath, Path outputPlaceholderPath) throws IOException {
        int maxWidth = Integer.parseInt(getConfigValue("placeholder_max_width", "360"));
        double quality = Double.parseDouble(getConfigValue("placeholder_quality", "0.6"));
        if (maxWidth <= 0) {
            throw new IOException("占位图最大宽度必须大于0");
        }
        if (!Double.isFinite(quality) || quality < 0.0 || quality > 1.0) {
            throw new IOException("占位图质量必须在0.0到1.0之间");
        }

        String ffmpegPath = getConfigValue("ffmpeg_path", "/usr/bin/ffmpeg");
        ExternalToolResolver.ToolResolution ffmpegResolution = resolveFfmpeg(ffmpegPath);
        externalToolResolver.requireAvailable(ffmpegResolution, "FFmpeg");

        Path input = originalImagePath.toAbsolutePath().normalize();
        Path output = outputPlaceholderPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException("原图片文件不存在或不可读: " + input);
        }
        Path outputDirectory = output.getParent();
        if (outputDirectory != null) {
            Files.createDirectories(outputDirectory);
        }

        // FFmpeg 的 JPEG 量化参数范围为2（最高质量）到31（最低质量）。
        int jpegQuality = Math.max(2, Math.min(31, 31 - (int) Math.round(quality * 29)));
        String scaleFilter = "scale=w='min(" + maxWidth + ",iw)':h=-2";
        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegResolution.resolvedPath(),
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-noautorotate",
                "-i", input.toString(),
                "-frames:v", "1",
                "-vf", scaleFilter,
                "-q:v", Integer.toString(jpegQuality),
                "-f", "image2",
                "-update", "1",
                output.toString());
        processBuilder.redirectErrorStream(true);

        boolean generated = false;
        try {
            Process process = processBuilder.start();
            boolean finished;
            try {
                finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("FFmpeg 占位图生成被中断", exception);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("FFmpeg 占位图生成超时(30s)");
            }

            String outputText = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("FFmpeg 占位图生成失败, 退出码: " + exitCode
                        + (outputText.isBlank() ? "" : ", 错误: " + outputText.strip()));
            }
            if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
                throw new IOException("FFmpeg 占位图生成完成但输出文件不存在或为空");
            }

            generated = true;
            return output;
        } finally {
            if (!generated) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // 保留 FFmpeg 的处理错误作为主要异常。
                }
            }
        }
    }

    public int[] extractDimensions(Path imagePath) throws IOException {
        int[] headerDimensions = ImageDimensionReader.read(imagePath);
        if (headerDimensions != null) {
            return headerDimensions;
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new IOException("无法读取图片获取尺寸: " + imagePath);
        }
        int[] dimensions = new int[2];
        dimensions[0] = image.getWidth();
        dimensions[1] = image.getHeight();
        return dimensions;
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

    private String getConfigValue(String key, String defaultValue) {
        ImageProcessingConfig config = ImageProcessingConfig.find("configKey", key).firstResult();
        if (config != null) {
            return config.configValue;
        }
        return defaultValue;
    }

    public ExternalToolResolver.ToolResolution resolveCwebp(String configuredPath) {
        return externalToolResolver.resolveTool(
                configuredPath,
                "/usr/bin/cwebp",
                "cwebp.exe",
                List.of(
                        "C:\\Program Files\\WebP\\bin\\cwebp.exe",
                        "C:\\Program Files (x86)\\WebP\\bin\\cwebp.exe"
                )
        );
    }
}

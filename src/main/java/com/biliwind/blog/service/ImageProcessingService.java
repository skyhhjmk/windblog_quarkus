package com.biliwind.blog.service;

import com.biliwind.blog.model.ImageProcessingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

@ApplicationScoped
public class ImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingService.class);

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
        String maxWidthStr = getConfigValue("placeholder_max_width", "360");
        String qualityStr = getConfigValue("placeholder_quality", "0.6");
        String formatStr = getConfigValue("placeholder_format", "jpeg");

        int maxWidth = Integer.parseInt(maxWidthStr);
        double quality = Double.parseDouble(qualityStr);

        BufferedImage originalImage = ImageIO.read(originalImagePath.toFile());
        if (originalImage == null) {
            throw new IOException("无法读取原图片: " + originalImagePath);
        }

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        double ratio = (double) maxWidth / originalWidth;
        if (ratio >= 1.0) {
            ratio = 1.0;
        }

        int targetWidth = (int) (originalWidth * ratio);
        int targetHeight = (int) (originalHeight * ratio);

        BufferedImage placeholderImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g2d = placeholderImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();

        ImageWriter writer = null;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatStr);
        if (writers.hasNext()) {
            writer = writers.next();
        }
        if (writer == null) {
            throw new IOException("找不到 " + formatStr + " 格式的图片写入器");
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputPlaceholderPath.toFile())) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) quality);

            writer.write(null, new javax.imageio.IIOImage(placeholderImage, null, null), param);
        } finally {
            writer.dispose();
        }

        return outputPlaceholderPath;
    }

    public int[] extractDimensions(Path imagePath) throws IOException {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new IOException("无法读取图片获取尺寸: " + imagePath);
        }
        int[] dimensions = new int[2];
        dimensions[0] = image.getWidth();
        dimensions[1] = image.getHeight();
        return dimensions;
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

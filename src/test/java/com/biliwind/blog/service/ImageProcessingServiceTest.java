package com.biliwind.blog.service;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ImageProcessingServiceTest {

    @jakarta.inject.Inject
    ImageProcessingService imageProcessingService;

    @TempDir
    Path tempDir;

    @Test
    void testExtractDimensions() throws IOException {
        Path testImage = createTestImage(100, 200);
        int[] dimensions = imageProcessingService.extractDimensions(testImage);
        assertEquals(100, dimensions[0]);
        assertEquals(200, dimensions[1]);
    }

    @Test
    void testGeneratePlaceholder() throws IOException {
        Path testImage = createTestImage(800, 600);
        Path outputPath = tempDir.resolve("placeholder.jpg");

        Path result = imageProcessingService.generatePlaceholder(testImage, outputPath);
        assertTrue(Files.exists(result));

        BufferedImage placeholder = ImageIO.read(result.toFile());
        assertTrue(placeholder.getWidth() <= 360);
    }

    private Path createTestImage(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();

        Path path = tempDir.resolve("test_" + width + "x" + height + ".jpg");
        ImageIO.write(image, "jpg", path.toFile());
        return path;
    }
}

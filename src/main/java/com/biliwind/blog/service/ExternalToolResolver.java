package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ExternalToolResolver {

    public ToolResolution resolveTool(String configuredPath, String linuxDefaultPath,
                                      String windowsExecutableName, List<String> windowsCommonPaths) {
        ArrayList<Path> candidates = new ArrayList<>();
        addConfiguredPath(candidates, configuredPath);
        addDefaultPath(candidates, linuxDefaultPath);
        addWindowsCommonPaths(candidates, windowsCommonPaths);
        addPathEnvironmentCandidates(candidates, windowsExecutableName);
        addPathEnvironmentCandidates(candidates, withoutExecutableExtension(windowsExecutableName));

        for (Path candidate : candidates) {
            Path normalizedCandidate = candidate.toAbsolutePath().normalize();
            if (isUsableTool(normalizedCandidate)) {
                String version = readVersion(normalizedCandidate);
                return new ToolResolution(true, normalizedCandidate.toString(), true, version,
                        "工具可用: " + normalizedCandidate);
            }
        }

        String requestedPath = configuredPath;
        if (requestedPath == null || requestedPath.isBlank()) {
            requestedPath = linuxDefaultPath;
        }
        return new ToolResolution(false, requestedPath, false, null,
                "工具不可用，已检查配置路径、常见安装目录和 PATH");
    }

    public void requireAvailable(ToolResolution resolution, String displayName) throws IOException {
        if (resolution == null || !resolution.available()) {
            String checkedPath = "";
            if (resolution != null && resolution.resolvedPath() != null) {
                checkedPath = resolution.resolvedPath();
            }
            throw new IOException(displayName + " 工具不可用: " + checkedPath);
        }
    }

    private void addConfiguredPath(List<Path> candidates, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        candidates.add(Path.of(configuredPath));
    }

    private void addDefaultPath(List<Path> candidates, String linuxDefaultPath) {
        if (linuxDefaultPath == null || linuxDefaultPath.isBlank()) {
            return;
        }
        candidates.add(Path.of(linuxDefaultPath));
    }

    private void addWindowsCommonPaths(List<Path> candidates, List<String> windowsCommonPaths) {
        if (!isWindows()) {
            return;
        }
        if (windowsCommonPaths == null) {
            return;
        }
        for (String commonPath : windowsCommonPaths) {
            if (commonPath != null && !commonPath.isBlank()) {
                candidates.add(Path.of(commonPath));
            }
        }
    }

    private void addPathEnvironmentCandidates(List<Path> candidates, String executableName) {
        if (executableName == null || executableName.isBlank()) {
            return;
        }
        String pathEnvironment = System.getenv("PATH");
        if (pathEnvironment == null || pathEnvironment.isBlank()) {
            return;
        }
        String[] pathParts = pathEnvironment.split(java.io.File.pathSeparator);
        for (String pathPart : pathParts) {
            if (pathPart != null && !pathPart.isBlank()) {
                candidates.add(Path.of(pathPart).resolve(executableName));
            }
        }
    }

    private String withoutExecutableExtension(String executableName) {
        if (executableName == null || executableName.isBlank()) {
            return executableName;
        }
        String lowerName = executableName.toLowerCase();
        if (lowerName.endsWith(".exe")) {
            return executableName.substring(0, executableName.length() - 4);
        }
        return executableName;
    }

    private boolean isUsableTool(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        if (isWindows()) {
            return true;
        }
        return Files.isExecutable(path);
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name");
        if (osName == null) {
            return false;
        }
        return osName.toLowerCase().contains("win");
    }

    private String readVersion(Path toolPath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(toolPath.toString(), "-version");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes());
            String[] lines = output.split("\\R");
            if (lines.length == 0) {
                return null;
            }
            return lines[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    public record ToolResolution(boolean available, String resolvedPath, boolean executable,
                                 String version, String message) {
    }
}

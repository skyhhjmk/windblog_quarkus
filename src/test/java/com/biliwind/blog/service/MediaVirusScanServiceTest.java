package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaVirusScanServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptOnlyCleanClamAvInstreamResponse() throws Exception {
        Path file = tempDir.resolve("sample.bin");
        Files.write(file, "safe sample".getBytes(StandardCharsets.UTF_8));

        try (ServerSocket server = new ServerSocket(0)) {
            Thread scanner = startFakeClamAv(server, "stream: OK\n");

            MediaVirusScanService service = new MediaVirusScanService();
            service.enabled = true;
            service.required = true;
            service.host = "127.0.0.1";
            service.port = server.getLocalPort();
            service.timeout = Duration.ofSeconds(3);

            MediaVirusScanService.ScanResult result = service.scan(file);

            scanner.join(3000);
            assertEquals(MediaVirusScanService.Status.CLEAN, result.status());
        }
    }

    @Test
    void shouldNotTreatRequiredButDisabledScannerAsClean() {
        MediaVirusScanService service = new MediaVirusScanService();
        service.enabled = false;
        service.required = true;

        MediaVirusScanService.ScanResult result = service.scan(tempDir.resolve("missing.bin"));

        assertEquals(MediaVirusScanService.Status.UNAVAILABLE, result.status());
    }

    @Test
    void shouldRespondToClamAvPing() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread probeThread = startFakeProbeServer(server, "PONG\0");

            MediaVirusScanService service = new MediaVirusScanService();
            service.enabled = true;
            service.required = true;
            service.host = "127.0.0.1";
            service.port = server.getLocalPort();
            service.timeout = Duration.ofSeconds(3);

            MediaVirusScanService.ProbeResult result = service.probe();

            probeThread.join(3000);
            assertEquals(MediaVirusScanService.ProbeStatus.AVAILABLE, result.status());
            assertEquals(true, result.available());
        }
    }

    private Thread startFakeClamAv(ServerSocket server, String response) {
        Thread scanner = new Thread(() -> {
            try (Socket socket = server.accept();
                 DataInputStream input = new DataInputStream(socket.getInputStream())) {
                readInstream(input);
                OutputStream output = socket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        scanner.start();
        return scanner;
    }

    private Thread startFakeProbeServer(ServerSocket server, String response) {
        Thread probe = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.getInputStream().readNBytes(6);
                OutputStream output = socket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        probe.start();
        return probe;
    }

    private void readInstream(DataInputStream input) throws IOException {
        byte[] command = new byte[10];
        input.readFully(command);
        while (true) {
            int size = input.readInt();
            if (size == 0) {
                return;
            }
            input.skipNBytes(size);
        }
    }
}

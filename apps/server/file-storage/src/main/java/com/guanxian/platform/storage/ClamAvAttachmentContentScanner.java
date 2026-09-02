package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "guanxian.storage.scan-mode", havingValue = "clamav")
final class ClamAvAttachmentContentScanner implements AttachmentContentScanner {
    private static final int CHUNK_SIZE = 8192;
    private final StorageProperties properties;

    ClamAvAttachmentContentScanner(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void assertClean(byte[] content) {
        Duration timeout = properties.getScanTimeout();
        if (properties.getScanHost().isBlank() || properties.getScanPort() < 1
                || properties.getScanPort() > 65535 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw unavailable("attachment malware scanner configuration is invalid", null);
        }
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getScanHost(), properties.getScanPort()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                int length = Math.min(CHUNK_SIZE, content.length - offset);
                output.writeInt(length);
                output.write(content, offset, length);
            }
            output.writeInt(0);
            output.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            for (int value; (value = socket.getInputStream().read()) >= 0 && value != 0;) {
                if (response.size() >= 4096) throw new IOException("scanner response exceeds limit");
                response.write(value);
            }
            String result = response.toString(StandardCharsets.US_ASCII).trim();
            if (result.endsWith(" FOUND")) {
                throw new ApiException(
                        "ATTACHMENT_MALWARE_DETECTED",
                        "attachment was rejected by the malware scanner",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (!result.endsWith(" OK")) {
                throw unavailable("attachment malware scanner returned an invalid result", null);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unavailable("attachment malware scanner is unavailable", exception);
        }
    }

    @Override
    public String scannerName() { return "clamav"; }

    private ApiException unavailable(String message, Exception cause) {
        ApiException exception = new ApiException(
                "ATTACHMENT_SCANNER_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) exception.initCause(cause);
        return exception;
    }
}

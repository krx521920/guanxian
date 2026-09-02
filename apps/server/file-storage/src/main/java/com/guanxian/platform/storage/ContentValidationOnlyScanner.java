package com.guanxian.platform.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development-only fallback. Production startup rejects this mode. */
@Component
@ConditionalOnProperty(name = "guanxian.storage.scan-mode", havingValue = "content-only", matchIfMissing = true)
final class ContentValidationOnlyScanner implements AttachmentContentScanner {
    @Override
    public void assertClean(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("attachment content must not be empty");
        }
    }

    @Override
    public String scannerName() { return "content-validation-only"; }
}

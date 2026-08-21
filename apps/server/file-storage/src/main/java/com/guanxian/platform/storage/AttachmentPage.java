package com.guanxian.platform.storage;

import java.util.List;

public record AttachmentPage(
        List<AttachmentView> items,
        int page,
        int size,
        long total) {
}

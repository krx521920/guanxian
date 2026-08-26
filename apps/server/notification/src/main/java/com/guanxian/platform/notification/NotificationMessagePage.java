package com.guanxian.platform.notification;

import java.util.List;

public record NotificationMessagePage(
        List<NotificationMessageView> items,
        long total,
        int page,
        int size) {
}

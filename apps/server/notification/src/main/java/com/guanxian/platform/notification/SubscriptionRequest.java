package com.guanxian.platform.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record SubscriptionRequest(
        @NotBlank @Size(max = 64) String subscriptionType,
        Map<String, Object> filters,
        List<@NotBlank @Size(max = 32) String> channels) {
}

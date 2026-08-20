package com.guanxian.platform.shared.api;

import java.time.Instant;

public record ApiResponse<T>(String code, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data, Instant.now());
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, Instant.now());
    }
}

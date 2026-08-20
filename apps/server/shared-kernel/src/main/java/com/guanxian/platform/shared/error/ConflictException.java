package com.guanxian.platform.shared.error;

import org.springframework.http.HttpStatus;

public final class ConflictException extends ApiException {
    public ConflictException(String message) {
        super("RESOURCE_CONFLICT", message, HttpStatus.CONFLICT);
    }
}

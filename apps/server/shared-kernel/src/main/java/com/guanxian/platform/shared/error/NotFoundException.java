package com.guanxian.platform.shared.error;

import org.springframework.http.HttpStatus;

public final class NotFoundException extends ApiException {
    public NotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", resource + " not found: " + id, HttpStatus.NOT_FOUND);
    }
}

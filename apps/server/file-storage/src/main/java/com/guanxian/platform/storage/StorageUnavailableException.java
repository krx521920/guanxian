package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

final class StorageUnavailableException extends ApiException {
    StorageUnavailableException(String message) {
        super("STORAGE_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    StorageUnavailableException(String message, Throwable cause) {
        this(message);
        initCause(cause);
    }
}

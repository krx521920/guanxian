package com.guanxian.platform.shared.error;

import org.springframework.http.HttpStatus;

public class PreconditionFailedException extends ApiException {
    public PreconditionFailedException(String message) {
        super("PRECONDITION_FAILED", message, HttpStatus.PRECONDITION_FAILED);
    }
}

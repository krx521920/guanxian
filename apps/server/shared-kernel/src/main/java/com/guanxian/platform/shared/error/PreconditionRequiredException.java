package com.guanxian.platform.shared.error;

import org.springframework.http.HttpStatus;

public class PreconditionRequiredException extends ApiException {
    public PreconditionRequiredException(String message) {
        super("PRECONDITION_REQUIRED", message, HttpStatus.PRECONDITION_REQUIRED);
    }
}

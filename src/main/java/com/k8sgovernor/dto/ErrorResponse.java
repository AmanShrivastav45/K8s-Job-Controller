package com.k8sgovernor.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ErrorResponse(int status, String error, String message, OffsetDateTime timestamp) {

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, OffsetDateTime.now(ZoneOffset.UTC));
    }
}

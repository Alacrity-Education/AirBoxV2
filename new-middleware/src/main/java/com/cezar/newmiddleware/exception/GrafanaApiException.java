package com.cezar.newmiddleware.exception;

public class GrafanaApiException extends RuntimeException {
    public GrafanaApiException(String message) {
        super(message);
    }

    public GrafanaApiException(String message, Throwable throwable) {
        super(message, throwable);
    }
}

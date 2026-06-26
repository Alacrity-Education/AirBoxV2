package com.cezar.newmiddleware.exception;

public class UnknownApiKeyException extends RuntimeException {

    public UnknownApiKeyException() {
        super(null, null, false, false);
    }
}

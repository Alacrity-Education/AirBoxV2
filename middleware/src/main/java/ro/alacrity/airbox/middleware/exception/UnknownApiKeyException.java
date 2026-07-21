package ro.alacrity.airbox.middleware.exception;

public class UnknownApiKeyException extends RuntimeException {

    public UnknownApiKeyException() {
        super(null, null, false, false);
    }
}

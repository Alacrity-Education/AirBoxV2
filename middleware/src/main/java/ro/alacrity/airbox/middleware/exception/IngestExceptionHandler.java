package ro.alacrity.airbox.middleware.exception;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IngestExceptionHandler {

    private static final Logger log = LogManager.getLogger(IngestExceptionHandler.class);

    // Data validation error -> 400
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Void> onValidationError(ValidationException e) {
        log.warn("Dropped data point: owner={}, validation={}", e.getOwnerEmail(), e.getValidationKind());
        return ResponseEntity.status(HttpStatusCode.valueOf(400)).build();
    }

    // Unknown ApiKey -> 401, logged NOWHERE (spec). Do not add a logger here.
    @ExceptionHandler(UnknownApiKeyException.class)
    public ResponseEntity<Void> onUknownApiKey(UnknownApiKeyException e) {
        return ResponseEntity.status(HttpStatusCode.valueOf(401)).build();
    }
}

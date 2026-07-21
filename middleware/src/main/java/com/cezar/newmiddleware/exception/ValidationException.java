package com.cezar.newmiddleware.exception;

public class ValidationException extends RuntimeException {

    private final String ownerEmail;
    private final ValidationKind validationKind;

    public ValidationException(String ownerEmail, ValidationKind validationKind) {
        super(null, null, false, false);
        this.ownerEmail = ownerEmail;
        this.validationKind = validationKind;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public ValidationKind getValidationKind() {
        return validationKind;
    }
}

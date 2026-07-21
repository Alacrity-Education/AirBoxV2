package ro.alacrity.airbox.middleware.exception;

public enum ValidationKind {
    MALFORMED_PAYLOAD,
    MISSING_GEOHASH,
    CHARGE_OUT_OF_RANGE
}

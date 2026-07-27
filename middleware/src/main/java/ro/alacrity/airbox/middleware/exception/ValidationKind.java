package ro.alacrity.airbox.middleware.exception;

public enum ValidationKind {
    MALFORMED_PAYLOAD,
    MISSING_GEOHASH,
    CHARGE_OUT_OF_RANGE,
    VOC_RAW_OUT_OF_RANGE,
    NOX_RAW_OUT_OF_RANGE
}

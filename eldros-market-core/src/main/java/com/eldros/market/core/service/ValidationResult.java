package com.eldros.market.core.service;

/** Either OK ({@code code == null}) or the first rule that rejected the request. */
public record ValidationResult(ValidationCode code) {

    public static final ValidationResult OK = new ValidationResult(null);

    public static ValidationResult rejected(ValidationCode code) {
        return new ValidationResult(code);
    }

    public boolean isOk() {
        return code == null;
    }
}

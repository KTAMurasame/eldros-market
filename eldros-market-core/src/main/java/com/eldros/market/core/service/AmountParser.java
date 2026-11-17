package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Parses player-typed amounts. Deliberately strict: plain ASCII digits with an optional single '.' fraction.
 * No exponents, signs, NaN/Infinity, thousands separators or ',' decimals (a Turkish "1,000" would otherwise
 * be read as 1). Invalid input never reaches the engine or Vault (docs/02 F-SEC-9, F0 B6).
 */
public final class AmountParser {

    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");

    public record Result<T>(T value, ValidationCode error) {
        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> fail(ValidationCode error) {
            return new Result<>(null, error);
        }

        public boolean isOk() {
            return error == null;
        }
    }

    private AmountParser() {
    }

    public static Result<Money> parseMoney(String text, MoneyScale scale) {
        if (text == null || text.isBlank()) {
            return Result.fail(ValidationCode.EMPTY);
        }
        String trimmed = text.trim();
        boolean negative = trimmed.startsWith("-");
        String body = negative ? trimmed.substring(1) : trimmed;
        if (!DECIMAL.matcher(body).matches()) {
            return Result.fail(ValidationCode.NOT_A_NUMBER);
        }
        if (negative) {
            return Result.fail(ValidationCode.NEGATIVE);
        }
        int dot = body.indexOf('.');
        if (dot >= 0 && body.length() - dot - 1 > scale.decimalPlaces()) {
            return Result.fail(ValidationCode.TOO_MANY_DECIMALS);
        }
        try {
            return Result.ok(Money.ofMinor(new BigDecimal(body).movePointRight(scale.decimalPlaces()).longValueExact()));
        } catch (ArithmeticException e) {
            return Result.fail(ValidationCode.TOO_LARGE);
        }
    }

    /** Quantities are item counts: whole numbers that fit in an {@code int}. Zero is left to {@link LimitPolicy}. */
    public static Result<Long> parseQuantity(String text) {
        if (text == null || text.isBlank()) {
            return Result.fail(ValidationCode.EMPTY);
        }
        String trimmed = text.trim();
        boolean negative = trimmed.startsWith("-");
        String body = negative ? trimmed.substring(1) : trimmed;
        if (!INTEGER.matcher(body).matches()) {
            return Result.fail(ValidationCode.NOT_A_NUMBER);
        }
        if (negative) {
            return Result.fail(ValidationCode.NEGATIVE);
        }
        try {
            return Result.ok((long) Integer.parseInt(body));
        } catch (NumberFormatException e) {
            return Result.fail(ValidationCode.TOO_LARGE);
        }
    }
}

package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses player-typed amounts. Deliberately strict: plain ASCII digits with an optional single '.' fraction,
 * optionally followed by one shorthand suffix: {@code k} (thousand), {@code m} (million) or {@code b} (billion),
 * case-insensitive, e.g. {@code 2.5k}. No exponents, signs, NaN/Infinity, thousands separators or ',' decimals
 * (a Turkish "1,000" would otherwise be read as 1). Invalid input never reaches the engine or Vault
 * (docs/02 F-SEC-9, F0 B6). A suffixed amount must still fit the economy's scale exactly; it is never rounded.
 */
public final class AmountParser {

    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");
    private static final Pattern SUFFIXED = Pattern.compile("([0-9]+(\\.[0-9]+)?)([kKmMbB])");

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
        Matcher suffixed = SUFFIXED.matcher(body);
        if (suffixed.matches()) {
            if (negative) {
                return Result.fail(ValidationCode.NEGATIVE);
            }
            BigDecimal value = expand(suffixed).stripTrailingZeros();
            if (value.scale() > scale.decimalPlaces()) {
                return Result.fail(ValidationCode.TOO_MANY_DECIMALS);
            }
            try {
                return Result.ok(Money.ofMinor(value.movePointRight(scale.decimalPlaces()).longValueExact()));
            } catch (ArithmeticException e) {
                return Result.fail(ValidationCode.TOO_LARGE);
            }
        }
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
        Matcher suffixed = SUFFIXED.matcher(body);
        if (suffixed.matches()) {
            if (negative) {
                return Result.fail(ValidationCode.NEGATIVE);
            }
            BigDecimal value = expand(suffixed).stripTrailingZeros();
            if (value.scale() > 0) {
                return Result.fail(ValidationCode.NOT_A_NUMBER);
            }
            try {
                return Result.ok((long) value.intValueExact());
            } catch (ArithmeticException e) {
                return Result.fail(ValidationCode.TOO_LARGE);
            }
        }
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

    private static BigDecimal expand(Matcher suffixed) {
        int power = switch (Character.toLowerCase(suffixed.group(3).charAt(0))) {
            case 'k' -> 3;
            case 'm' -> 6;
            default -> 9; // 'b'; the pattern admits nothing else
        };
        return new BigDecimal(suffixed.group(1)).movePointRight(power);
    }
}

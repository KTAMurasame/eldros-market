package com.eldros.market.core.domain;

/**
 * Non-negative amount in minor units (e.g. cents). Never a {@code double}: conversion to the
 * economy provider happens only at the Vault boundary via {@link MoneyScale}. Overflow always throws.
 */
public record Money(long minor) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (minor < 0) {
            throw new IllegalArgumentException("money must not be negative: " + minor);
        }
    }

    public static Money ofMinor(long minor) {
        return new Money(minor);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minor, other.minor));
    }

    /** @throws ArithmeticException if the result would be negative */
    public Money minus(Money other) {
        long result = minor - other.minor;
        if (result < 0) {
            throw new ArithmeticException("money would become negative: " + minor + " - " + other.minor);
        }
        return new Money(result);
    }

    public Money times(long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative: " + quantity);
        }
        return new Money(Math.multiplyExact(minor, quantity));
    }

    public boolean isGreaterThan(Money other) {
        return minor > other.minor;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(minor, other.minor);
    }
}

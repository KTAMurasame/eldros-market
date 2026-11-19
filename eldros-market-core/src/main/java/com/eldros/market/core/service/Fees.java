package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Sales tax and order fees in basis points (1 bp = 0.01%, 10 000 bp = 100%). Every amount is rounded down in the
 * player's favour, so a fee never takes a fraction of a minor unit the player did not owe. Fees leave the economy
 * (a money sink); nothing here moves money.
 */
public final class Fees {

    public static final int FULL = 10_000;
    private static final BigInteger FULL_BIG = BigInteger.valueOf(FULL);

    private Fees() {
    }

    /** {@code gross * bps / 10000}, rounded down. */
    public static Money tax(Money gross, int bps) {
        requireRate(bps);
        return Money.ofMinor(BigInteger.valueOf(gross.minor()).multiply(BigInteger.valueOf(bps)).divide(FULL_BIG).longValueExact());
    }

    /** {@code flat + value * bps / 10000} (rounded down), at most {@code cap} unless the cap is zero (no cap). */
    public static Money creationFee(Money orderValue, Money flat, int bps, Money cap) {
        Money fee = flat.plus(tax(orderValue, bps));
        return cap.minor() > 0 && fee.compareTo(cap) > 0 ? cap : fee;
    }

    /** "2.5" (percent) → 250 bp. At most two decimals, 0 to 100 inclusive. */
    public static int basisPoints(String percent) {
        BigDecimal p;
        try {
            p = new BigDecimal(percent.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a percentage: " + percent);
        }
        BigDecimal bps = p.movePointRight(2);
        if (p.signum() < 0 || bps.compareTo(BigDecimal.valueOf(FULL)) > 0) {
            throw new IllegalArgumentException("percentage must be between 0 and 100: " + percent);
        }
        try {
            return bps.setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("at most two decimals: " + percent);
        }
    }

    private static void requireRate(int bps) {
        if (bps < 0 || bps > FULL) {
            throw new IllegalArgumentException("rate must be 0..10000 bp: " + bps);
        }
    }
}

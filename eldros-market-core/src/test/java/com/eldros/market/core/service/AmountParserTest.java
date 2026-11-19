package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AmountParserTest {

    private static final MoneyScale TWO_DECIMALS = MoneyScale.of(2);

    // Break: a valid price is read with the wrong magnitude (e.g. "12.5" becomes 125 minor units).
    @ParameterizedTest
    @CsvSource({
            "12, 1200",
            "12.5, 1250",
            "12.50, 1250",
            "0.01, 1",
            "' 7 ', 700",
            "0, 0",
    })
    void parsesPlainDecimalPrices(String text, long expectedMinor) {
        AmountParser.Result<Money> result = AmountParser.parseMoney(text, TWO_DECIMALS);

        assertThat(result.isOk()).isTrue();
        assertThat(result.value()).isEqualTo(Money.ofMinor(expectedMinor));
    }

    // Break: exotic number syntax (NaN, exponents, signs, separators) is accepted and reaches the engine or Vault.
    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "1e3", "1e309", "0x10", "+5", "1,5", "1.2.3", ".5", "5.", "12a", "١٢"})
    void rejectsAnythingButPlainAsciiDecimals(String text) {
        assertThat(AmountParser.parseMoney(text, TWO_DECIMALS).error()).isEqualTo(ValidationCode.NOT_A_NUMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0", "-0.01"})
    void rejectsNegativeAmounts(String text) {
        assertThat(AmountParser.parseMoney(text, TWO_DECIMALS).error()).isEqualTo(ValidationCode.NEGATIVE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsEmptyInput(String text) {
        assertThat(AmountParser.parseMoney(text, TWO_DECIMALS).error()).isEqualTo(ValidationCode.EMPTY);
    }

    // Break: "12.345" is silently rounded/truncated instead of being refused.
    @Test
    void rejectsMoreDecimalsThanTheScale() {
        assertThat(AmountParser.parseMoney("12.345", TWO_DECIMALS).error()).isEqualTo(ValidationCode.TOO_MANY_DECIMALS);
        assertThat(AmountParser.parseMoney("1.5", MoneyScale.of(0)).error()).isEqualTo(ValidationCode.TOO_MANY_DECIMALS);
    }

    // Break: an amount above Long.MAX_VALUE minor units overflows into a small or negative value.
    @Test
    void rejectsAmountsThatDoNotFitInMinorUnits() {
        // Long.MAX_VALUE = 9223372036854775807; 92233720368547758.08 is one minor unit above it at scale 2.
        assertThat(AmountParser.parseMoney("92233720368547758.08", TWO_DECIMALS).error()).isEqualTo(ValidationCode.TOO_LARGE);
        assertThat(AmountParser.parseMoney("92233720368547758.07", TWO_DECIMALS).value()).isEqualTo(Money.ofMinor(Long.MAX_VALUE));
    }

    // Break: "2.5k" is refused or read as 2.5 instead of 2500; a suffix multiplies by the wrong power of ten.
    @ParameterizedTest
    @CsvSource({
            "2.5k, 250000",
            "2.5K, 250000",
            "1k, 100000",
            "1m, 100000000",
            "1.25m, 125000000",
            "3b, 300000000000",
            "1.2345k, 123450",
            "0.5k, 50000",
            "' 4k ', 400000",
    })
    void parsesThousandMillionBillionSuffixes(String text, long expectedMinor) {
        AmountParser.Result<Money> result = AmountParser.parseMoney(text, TWO_DECIMALS);

        assertThat(result.isOk()).isTrue();
        assertThat(result.value()).isEqualTo(Money.ofMinor(expectedMinor));
    }

    // Break: a suffix hides more precision than the economy has ("1.23456k" = 1234.56 is fine, 1234.567 is not).
    @Test
    void suffixResultMustFitTheScale() {
        assertThat(AmountParser.parseMoney("1.234567k", TWO_DECIMALS).error()).isEqualTo(ValidationCode.TOO_MANY_DECIMALS);
        assertThat(AmountParser.parseMoney("1.5k", MoneyScale.of(0)).value()).isEqualTo(Money.ofMinor(1500));
        assertThat(AmountParser.parseMoney("1.0005k", MoneyScale.of(0)).error()).isEqualTo(ValidationCode.TOO_MANY_DECIMALS);
    }

    // Break: suffix syntax opens a door for odd input (double suffix, suffix alone, unknown letters).
    @ParameterizedTest
    @ValueSource(strings = {"k", "1kk", "1km", "1t", "1 k", "k1", ".5k", "1.k", "1e3k"})
    void rejectsMalformedSuffixes(String text) {
        assertThat(AmountParser.parseMoney(text, TWO_DECIMALS).error()).isEqualTo(ValidationCode.NOT_A_NUMBER);
    }

    @Test
    void suffixOverflowIsTooLargeAndNegativeIsNegative() {
        assertThat(AmountParser.parseMoney("99999999999b", TWO_DECIMALS).error()).isEqualTo(ValidationCode.TOO_LARGE);
        assertThat(AmountParser.parseMoney("-1k", TWO_DECIMALS).error()).isEqualTo(ValidationCode.NEGATIVE);
    }

    @ParameterizedTest
    @CsvSource({"2k, 2000", "1.5k, 1500", "0.064k, 64"})
    void parsesQuantitySuffixes(String text, long expected) {
        assertThat(AmountParser.parseQuantity(text).value()).isEqualTo(expected);
    }

    // Break: a fractional item count sneaks through the suffix path, or a huge one wraps around.
    @Test
    void rejectsFractionalOrHugeQuantitiesWithSuffix() {
        assertThat(AmountParser.parseQuantity("0.0005k").error()).isEqualTo(ValidationCode.NOT_A_NUMBER);
        assertThat(AmountParser.parseQuantity("3b").error()).isEqualTo(ValidationCode.TOO_LARGE);
        assertThat(AmountParser.parseQuantity("1kk").error()).isEqualTo(ValidationCode.NOT_A_NUMBER);
    }

    @ParameterizedTest
    @CsvSource({"64, 64", "1, 1", "0, 0", "' 3 ', 3"})
    void parsesQuantities(String text, long expected) {
        assertThat(AmountParser.parseQuantity(text).value()).isEqualTo(expected);
    }

    @Test
    void rejectsInvalidQuantities() {
        assertThat(AmountParser.parseQuantity("1.5").error()).isEqualTo(ValidationCode.NOT_A_NUMBER);
        assertThat(AmountParser.parseQuantity("-3").error()).isEqualTo(ValidationCode.NEGATIVE);
        assertThat(AmountParser.parseQuantity("").error()).isEqualTo(ValidationCode.EMPTY);
        assertThat(AmountParser.parseQuantity("2147483648").error()).isEqualTo(ValidationCode.TOO_LARGE);
        assertThat(AmountParser.parseQuantity("2147483647").value()).isEqualTo(2_147_483_647L);
    }
}

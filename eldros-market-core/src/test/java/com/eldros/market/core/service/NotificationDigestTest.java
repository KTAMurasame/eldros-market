package com.eldros.market.core.service;

import com.eldros.market.core.service.NotificationDigest.Entry;
import com.eldros.market.core.service.NotificationDigest.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDigestTest {

    private static final String SOLD = "notify.trade.sold";
    private static final String BOUGHT = "notify.trade.bought";

    private static Entry e(long id, String key, Long... args) {
        return new Entry(id, key, List.of(args));
    }

    // Break: a busy sell order still produces one chat line per fill.
    @Test
    void mergesFillsOfTheSameItemAtTheSamePrice() {
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, SOLD, 7L, 4L, 5100L),
                e(2, SOLD, 7L, 2L, 5100L),
                e(3, SOLD, 7L, 6L, 5100L)));

        assertThat(lines).containsExactly(new Line(SOLD, List.of(7L, 12L, 5100L), List.of(1L, 2L, 3L)));
    }

    // Break: fills at different prices are merged, so the shown price is wrong for some of the items.
    @Test
    void keepsDifferentPricesItemsAndSidesApart() {
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, SOLD, 7L, 4L, 5100L),
                e(2, SOLD, 7L, 2L, 5200L),
                e(3, SOLD, 8L, 1L, 5100L),
                e(4, BOUGHT, 7L, 3L, 5100L),
                e(5, SOLD, 7L, 1L, 5100L)));

        assertThat(lines).containsExactly(
                new Line(SOLD, List.of(7L, 5L, 5100L), List.of(1L, 5L)),
                new Line(SOLD, List.of(7L, 2L, 5200L), List.of(2L)),
                new Line(SOLD, List.of(8L, 1L, 5100L), List.of(3L)),
                new Line(BOUGHT, List.of(7L, 3L, 5100L), List.of(4L)));
    }

    @Test
    void mergesReturnsPerItemAndRefundsIntoOneAmount() {
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, "notify.order.cancelled", 7L, 3L),
                e(2, "notify.order.cancelled", 7L, 5L),
                e(3, "notify.recovery.refund", 250L),
                e(4, "notify.recovery.refund", 100L),
                e(5, "notify.order.expired", 7L, 1L)));

        assertThat(lines).containsExactly(
                new Line("notify.order.cancelled", List.of(7L, 8L), List.of(1L, 2L)),
                new Line("notify.recovery.refund", List.of(350L), List.of(3L, 4L)),
                new Line("notify.order.expired", List.of(7L, 1L), List.of(5L)));
    }

    // Break: an unknown or argument-less key (e.g. "under review") is dropped or its rows are never marked delivered.
    @Test
    void identicalOtherMessagesCollapseAndEveryIdIsKept() {
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, "notify.recovery.review"),
                e(2, "notify.recovery.review"),
                e(3, "notify.something.new", 1L, 2L),
                e(4, "notify.something.new", 1L, 3L)));

        assertThat(lines).containsExactly(
                new Line("notify.recovery.review", List.of(), List.of(1L, 2L)),
                new Line("notify.something.new", List.of(1L, 2L), List.of(3L)),
                new Line("notify.something.new", List.of(1L, 3L), List.of(4L)));
        assertThat(lines.stream().mapToInt(l -> l.ids().size()).sum()).isEqualTo(4);
    }

    // Break: a malformed row (wrong argument count) throws and blocks every later notification of the player.
    @Test
    void malformedRowsStayOnTheirOwnLine() {
        List<Line> lines = NotificationDigest.digest(List.of(e(1, SOLD, 7L), e(2, SOLD, 7L)));

        assertThat(lines).hasSize(2);
    }

    // Break: summing quantities overflows into a negative count.
    @Test
    void overflowStartsANewLineInsteadOfWrapping() {
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, "notify.recovery.refund", Long.MAX_VALUE),
                e(2, "notify.recovery.refund", 1L)));

        assertThat(lines).containsExactly(
                new Line("notify.recovery.refund", List.of(Long.MAX_VALUE), List.of(1L)),
                new Line("notify.recovery.refund", List.of(1L), List.of(2L)));
    }

    // Break: the sender indexes into a short argument list and the whole delivery task throws for every player.
    @Test
    void reportsWhetherAKnownKeyHasItsArguments() {
        assertThat(NotificationDigest.hasExpectedArgs(SOLD, List.of(7L, 1L, 5100L))).isTrue();
        assertThat(NotificationDigest.hasExpectedArgs(SOLD, List.of(7L))).isFalse();
        assertThat(NotificationDigest.hasExpectedArgs("notify.recovery.refund", List.of())).isFalse();
        assertThat(NotificationDigest.hasExpectedArgs("notify.recovery.review", List.of())).isTrue();
        assertThat(NotificationDigest.hasExpectedArgs("notify.something.new", List.of(1L))).isTrue();
    }

    // Break: merged taxed sales show the tax of only the first fill, or are merged with untaxed ones.
    @Test
    void taxedSalesSumQuantityAndTaxTogether() {
        String taxed = "notify.trade.sold-taxed";
        List<Line> lines = NotificationDigest.digest(List.of(
                e(1, taxed, 7L, 4L, 5100L, 51L),
                e(2, taxed, 7L, 2L, 5100L, 25L),
                e(3, SOLD, 7L, 1L, 5100L)));

        assertThat(lines).containsExactly(
                new Line(taxed, List.of(7L, 6L, 5100L, 76L), List.of(1L, 2L)),
                new Line(SOLD, List.of(7L, 1L, 5100L), List.of(3L)));
        assertThat(NotificationDigest.hasExpectedArgs(taxed, List.of(7L, 1L, 5100L))).isFalse();
    }

    @Test
    void emptyInputGivesNoLines() {
        assertThat(NotificationDigest.digest(List.of())).isEmpty();
    }
}

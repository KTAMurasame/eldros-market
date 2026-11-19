package com.eldros.market.paper;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutoCollectPolicyTest {

    private static final PlayerId P = new PlayerId(UUID.randomUUID());

    private static MailboxRow money(long id, String status) {
        return new MailboxRow(id, P, "MONEY", null, 0, Money.ofMinor(100 * id), status, null, 0, "TRADE_MONEY");
    }

    private static MailboxRow items(long id) {
        return new MailboxRow(id, P, "ITEM", new TemplateId(1), 5, null, "AVAILABLE", null, 0, "TRADE_ITEMS");
    }

    // Break: items are auto-collected (a full inventory would drop or lose them) or a row being claimed is claimed twice.
    @Test
    void onlyAvailableMoneyIsPicked() {
        List<MailboxRow> rows = List.of(money(1, "AVAILABLE"), items(2), money(3, "CLAIMING"), money(4, "QUARANTINED"), money(5, "AVAILABLE"));
        assertThat(AutoCollectPolicy.pick(rows, 10)).extracting(MailboxRow::id).containsExactly(1L, 5L);
    }

    // Break: hundreds of rows are claimed in one tick (each one writes the journal and an fsynced effect).
    @Test
    void atMostMaxRowsPerRound() {
        List<MailboxRow> rows = List.of(money(1, "AVAILABLE"), money(2, "AVAILABLE"), money(3, "AVAILABLE"));
        assertThat(AutoCollectPolicy.pick(rows, 2)).extracting(MailboxRow::id).containsExactly(1L, 2L);
    }

    // Break: an economy that keeps refusing makes the market retry (and log) every few seconds forever.
    @Test
    void aFailureWaitsBeforeTheNextTry() {
        AutoCollectPolicy.Backoff b = new AutoCollectPolicy.Backoff(60_000);
        UUID id = UUID.randomUUID();
        assertThat(b.ready(id, 1_000)).isTrue();
        b.failed(id, 1_000);
        assertThat(b.ready(id, 30_000)).isFalse();
        assertThat(b.ready(id, 61_000)).isTrue();
        b.failed(id, 61_000);
        b.forget(id);
        assertThat(b.ready(id, 61_001)).isTrue();
    }
}

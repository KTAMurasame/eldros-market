package com.eldros.market.core.journal;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;

/** One external operation's intent record (docs/03 §5 journal, §7). Immutable snapshot of a row. */
public record JournalEntry(String opId, Type type, PlayerId player, State state, TemplateId template, Long qty,
                           Long qtyEffective, Money unitPrice, Money amount, Long mailboxId, Long orderId,
                           String quarantineReason, Long observedMarkerQty, int attempts, long createdAt) {

    public enum Type { BUY_CREATE, SELL_CREATE, CLAIM_MONEY, CLAIM_ITEM }

    public enum State {
        PENDING, APPLIED, ABORTED, RECOVERED, QUARANTINED, RESOLVED_APPLIED, RESOLVED_NOT_APPLIED;

        public boolean isTerminal() {
            return this != PENDING && this != QUARANTINED;
        }
    }

    public static JournalEntry pending(String opId, Type type, PlayerId player, TemplateId template, Long qty,
                                       Money unitPrice, Money amount, Long mailboxId, long now) {
        return new JournalEntry(opId, type, player, State.PENDING, template, qty, null, unitPrice, amount, mailboxId,
                null, null, null, 0, now);
    }
}

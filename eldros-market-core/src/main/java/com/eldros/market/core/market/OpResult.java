package com.eldros.market.core.market;

import com.eldros.market.core.engine.MatchPlan;

/** Outcome of one player operation, for the paper module to turn into a message. */
public record OpResult(Status status, Reason reason, String opId, MatchPlan plan, long quantity) {

    public enum Status { DONE, REJECTED, QUARANTINED }

    public enum Reason { NONE, INSUFFICIENT_FUNDS, NOT_ENOUGH_ITEMS, INVENTORY_FULL, NOT_AVAILABLE, NOT_OWNER, SAFE_MODE, ORDER_CHANGED }

    static OpResult done(String opId, MatchPlan plan, long quantity) {
        return new OpResult(Status.DONE, Reason.NONE, opId, plan, quantity);
    }

    static OpResult rejected(String opId, Reason reason) {
        return new OpResult(Status.REJECTED, reason, opId, null, 0);
    }

    static OpResult quarantined(String opId) {
        return new OpResult(Status.QUARANTINED, Reason.NONE, opId, null, 0);
    }
}

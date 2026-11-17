package com.eldros.market.core.engine;

import com.eldros.market.core.domain.Order;

import java.util.List;

/**
 * Result of matching one incoming order, computed without changing the book. Storage persists it in one
 * transaction and only then the engine {@link MatchingEngine#apply applies} it (docs/03 §6.2). The
 * "remaining before" snapshots let {@code apply} refuse a plan that no longer fits the book.
 */
public record MatchPlan(Order incomingOrder, List<Fill> fills, long incomingRemainingBefore, List<Long> restingRemainingBefore) {
}

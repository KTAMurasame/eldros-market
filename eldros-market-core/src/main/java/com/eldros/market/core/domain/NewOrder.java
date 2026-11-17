package com.eldros.market.core.domain;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;

/** A validated request for a new order, before storage assigns its id and seq. {@code expiresAt} may be null. */
public record NewOrder(PlayerId owner, OrderSide side, TemplateId template, Money unitPrice, long quantity, Long expiresAt) {
}

package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;

/** Ending an active order: buy escrow or remaining sell items go back to the owner's mailbox. */
public record CancelPlan(long orderId, PlayerId owner, OrderSide side, TemplateId template,
                         Money refundMoney, long refundItems, long remaining, OrderStatus finalStatus) {
}

package com.ticketing.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Typed row for {@code tb_ticket_sku}. Stock mutation must still use
 * {@link com.ticketing.repository.TicketInventoryRepository#tryDecreaseStock} CAS —
 * not load-mutate-save.
 */
@lombok.Data
public class TicketSku {
    private String id;
    @JsonProperty("event_session_id")
    private String eventSessionId;
    private String name;
    private BigDecimal price;
    @JsonProperty("total_stock")
    private int totalStock;
    @JsonProperty("remaining_stock")
    private int remainingStock;
    @JsonProperty("sale_start_time")
    private Timestamp saleStartTime;
    @JsonProperty("sale_end_time")
    private Timestamp saleEndTime;
    @JsonProperty("purchase_limit")
    private int purchaseLimit;
    @JsonProperty("single_purchase_limit")
    private int singlePurchaseLimit = 100;
    private String status;
    private int version;
}

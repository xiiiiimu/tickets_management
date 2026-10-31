package com.ticketing.order;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Typed row for {@code tb_ticket_order}. Mutable status/version are updated only after a
 * successful {@link com.ticketing.repository.OrderRepository#tryTransition} CAS -
 * this is not an ORM entity.
 */
@lombok.Data
public class TicketOrder {
    private String id;
    @JsonProperty("order_no")
    private String orderNo;
    @JsonProperty("reservation_id")
    private String reservationId;
    @JsonProperty("user_id")
    private long userId;
    @JsonProperty("sku_id")
    private String skuId;
    private int quantity;
    private BigDecimal amount;
    @JsonProperty("device_id")
    private String deviceId;
    private String ip;
    private String status;
    private int version;
    @JsonProperty("trace_id")
    private String traceId;
    @JsonProperty("expires_at")
    private Timestamp expiresAt;
    @JsonProperty("created_at")
    private Timestamp createdAt;
    @JsonProperty("updated_at")
    private Timestamp updatedAt;
}
package com.ticketing.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.Timestamp;

/**
 * Typed row for {@code tb_reserve_request}. Data carrier only; CAS / status transitions
 * stay on {@link com.ticketing.repository.ReservationIntentRepository}.
 */
@lombok.Data
public class ReservationIntent {
    @JsonProperty("request_id")
    private String requestId;
    private String fingerprint;
    @JsonProperty("reservation_id")
    private String reservationId;
    @JsonProperty("order_id")
    private String orderId;
    @JsonProperty("user_id")
    private long userId;
    @JsonProperty("sku_id")
    private String skuId;
    private int quantity;
    @JsonProperty("device_id")
    private String deviceId;
    private String ip;
    @JsonProperty("trace_id")
    private String traceId;
    private String status;
    /** Transient: Lua-selected bucket, persisted by the reserve transaction in its allocation row. */
    private Integer inventoryBucket;
    /** Protocol 3, nonzero components in ascending bucket order; persisted as allocation items. */
    private java.util.Map<Integer,Integer> inventoryAllocation;
    /** V2 server-time expiry read back from the atomic Redis hold; never initialized while queued. */
    private Timestamp expiresAt;
    @JsonProperty("created_at")
    private Timestamp createdAt;
}

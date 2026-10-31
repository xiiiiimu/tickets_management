package com.ticketing.inventory;

import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.ticket.TicketSku;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Round-1: already-initialized SKU must not take SKU FOR UPDATE on ensureReady.
 */
class InventoryEnsureReadyTest {

    StringRedisTemplate redis;
    TicketInventoryRepository skus;
    ReservationIntentRepository intents;
    InventoryService inventory;
    HashOperations<String, Object, Object> hash;
    ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForValue()).thenReturn(values);
        skus = mock(TicketInventoryRepository.class);
        intents = mock(ReservationIntentRepository.class);
        inventory = new InventoryService(redis, skus, intents, new SimpleMeterRegistry(), new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:init-" + java.util.UUID.randomUUID(), "sa", "")));
    }

    private TicketSku sku(String id) {
        TicketSku s = new TicketSku();
        s.setId(id);
        s.setStatus("ON_SALE");
        s.setPurchaseLimit(2);
        s.setTotalStock(20);
        s.setRemainingStock(20);
        s.setSaleStartTime(new Timestamp(System.currentTimeMillis() - 1000));
        s.setSaleEndTime(new Timestamp(System.currentTimeMillis() + 3_600_000));
        return s;
    }

    @Test
    void ensureReadySkipsForUpdateWhenRedisAlreadyInitialized() {
        String id = "sku-ready";
        String p = InventoryService.prefix(id);
        when(redis.hasKey(p + "config")).thenReturn(true);
        when(redis.hasKey(p + "stock")).thenReturn(true);

        inventory.ensureReady(id);

        verify(skus, never()).findByIdForUpdate(any());
        verify(hash, never()).putAll(anyString(), anyMap());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void ensureReadyColdPathUsesConfigureForUpdateOnce() {
        String id = "sku-cold";
        String p = InventoryService.prefix(id);
        when(redis.hasKey(anyString())).thenReturn(false, false, true, true);
        when(skus.findByIdForUpdate(id)).thenReturn(sku(id));
        when(intents.countBySkuId(id)).thenReturn(0L);

        inventory.ensureReady(id);

        verify(skus, times(2)).findByIdForUpdate(id);
        verify(hash, times(1)).putAll(eq(p + "config"), anyMap());
        verify(values, times(1)).setIfAbsent(eq(p + "stock"), eq("20"));
    }
}

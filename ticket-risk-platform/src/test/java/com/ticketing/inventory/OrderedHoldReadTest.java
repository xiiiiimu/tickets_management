package com.ticketing.inventory;

import com.ticketing.common.BusinessException;
import com.ticketing.repository.*;
import com.ticketing.ticket.ReservationIntent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderedHoldReadTest {
    StringRedisTemplate redis;InventoryService inventory;ReservationIntent r;Map<Object,Object> marker;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        redis=mock(StringRedisTemplate.class);HashOperations<String,Object,Object> hash=mock(HashOperations.class);when(redis.opsForHash()).thenReturn(hash);
        inventory=new InventoryService(redis,mock(TicketInventoryRepository.class),mock(ReservationIntentRepository.class),new SimpleMeterRegistry(),mock(PlatformTransactionManager.class));
        r=new ReservationIntent();r.setSkuId("sku");r.setReservationId("res");r.setUserId(7);r.setQuantity(2);r.setFingerprint("fp");
        marker=new HashMap<>();marker.put("state","HELD");marker.put("fingerprint","fp");marker.put("protocolVersion","2");marker.put("assemblyVersion","3");marker.put("quantity","2");marker.put("userId","7");marker.put("bucketId","4");marker.put("stockKey",InventoryService.prefix("sku")+"stock:bucket:4");marker.put("expiresAt","1800000600000");
        when(hash.entries(InventoryService.prefix("sku")+"reservation:res")).thenReturn(marker);
    }
    @Test void readsOriginalAllocationAndDeadlineWithoutReserveOrRenewal(){inventory.loadRuntimeHold(r,3);assertEquals(4,r.getInventoryBucket());assertEquals(1800000600000L,r.getExpiresAt().getTime());verify(redis).opsForHash();verifyNoMoreInteractions(redis);}
    @Test void missingOrAbortedMarkerNeverReconstructsInventory(){marker.clear();assertThrows(BusinessException.class,()->inventory.loadRuntimeHold(r,3));assertNull(r.getInventoryBucket());assertNull(r.getExpiresAt());}
    @Test void mismatchedIdentityVersionQuantityOrStateCannotConfirm(){
        for(String key:Arrays.asList("state","fingerprint","protocolVersion","assemblyVersion","quantity","userId")){Object old=marker.put(key,"invalid");assertThrows(BusinessException.class,()->inventory.loadRuntimeHold(r,3));marker.put(key,old);}
    }
    @Test void bucketAndStockKeyMustMatchExistingSixteenBucketProtocol(){marker.put("bucketId","16");assertThrows(BusinessException.class,()->inventory.loadRuntimeHold(r,3));marker.put("bucketId","4");marker.put("stockKey","ticket:{other}:stock:bucket:4");assertThrows(BusinessException.class,()->inventory.loadRuntimeHold(r,3));}
}

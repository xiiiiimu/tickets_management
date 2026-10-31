package com.ticketing.ticket;
import com.ticketing.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReservationOwnershipTest {
 Map<String,Object> job(){Map<String,Object> j=new HashMap<>();j.put("request_id","request");j.put("fingerprint","fp");j.put("reservation_id","reservation");j.put("order_id","order");return j;}
 ReservationIntent intent(){ReservationIntent i=new ReservationIntent();i.setRequestId("request");i.setFingerprint("fp");i.setReservationId("reservation");i.setOrderId("order");return i;}
 @Test void exactDurableIdentityIsRequired(){assertDoesNotThrow(()->ReservationRuntimeRecovery.assertOwned(job(),intent()));}
 @Test void collidingLegacyOrDamagedIntentCannotBeCompensatedAsV2(){
  for(String field:Arrays.asList("request_id","fingerprint","reservation_id","order_id")){
   Map<String,Object> j=job();j.put(field,"another");assertThrows(BusinessException.class,()->ReservationRuntimeRecovery.assertOwned(j,intent()));
  }
 }
}

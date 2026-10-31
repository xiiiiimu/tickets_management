package com.ticketing.ticket;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import com.ticketing.common.dto.UserDTO;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.risk.RiskDecisionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class LegacyEntryIsolationTest {
 Db db=mock(Db.class);RiskDecisionService risk=mock(RiskDecisionService.class);InventoryService stock=mock(InventoryService.class);
 ReservationIntentService intents=mock(ReservationIntentService.class);ReservationTransactionService transactions=mock(ReservationTransactionService.class);
 TicketController controller=new TicketController(risk,intents,transactions,db,stock,new SimpleMeterRegistry());
 ReserveRequest request=new ReserveRequest();
 @BeforeEach void identity(){UserDTO user=new UserDTO();user.setId(1L);UserHolder.saveUser(user);request.setUserId(1L);request.setRequestId("request");request.setEventSessionId("session");ReflectionTestUtils.setField(controller,"priorityReservations",true);when(db.one(anyString(),eq("request"))).thenReturn(null);}
 @AfterEach void clear(){UserHolder.removeUser();}
 @Test void v2PreventsConcurrentLegacyAdmissionEvenWhenLegacyFlagIsTrue(){
  BusinessException error=assertThrows(BusinessException.class,()->controller.reserve(request,mock(HttpServletRequest.class)));
  assertEquals(503,error.status);verifyNoInteractions(risk,stock,intents,transactions);
 }
 @Test void existingCompletedRequestsStillReplayThroughLegacyApi(){
  Map<String,Object> previous=new HashMap<>();previous.put("status","COMPLETED");previous.put("fingerprint","fp");
  when(db.one(anyString(),eq("request"))).thenReturn(previous);when(intents.fingerprint(request)).thenReturn("fp");
  when(transactions.reserve("request","session")).thenReturn(Collections.singletonMap("orderId","old-order"));
  assertEquals(Collections.singletonMap("orderId","old-order"),controller.reserve(request,mock(HttpServletRequest.class)).getData());
  verifyNoInteractions(risk,stock);
 }
}

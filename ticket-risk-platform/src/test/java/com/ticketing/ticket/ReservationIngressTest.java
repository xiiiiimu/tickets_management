package com.ticketing.ticket;
import com.ticketing.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationIngressTest {
 ReservationV2Service backend=mock(ReservationV2Service.class);
 ReservationIntentService intents=mock(ReservationIntentService.class);
 SimpleMeterRegistry metrics=new SimpleMeterRegistry();
 MockEnvironment env=new MockEnvironment().withProperty("ticketing.ingress.workers","1").withProperty("ticketing.ingress.capacity","1");
 ReservationIngress ingress;CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(1);
 @BeforeEach void setup(){when(backend.isAccepting()).thenReturn(true);when(backend.lookupRequest(anyString(),anyLong())).thenReturn(null);when(intents.fingerprint(any())).thenAnswer(a->{ReserveRequest r=a.getArgument(0);return r.getUserId()+":"+r.getTicketSkuId()+":"+r.getQuantity();});}
 @AfterEach void close(){release.countDown();if(ingress!=null)ingress.close();}
 ReserveRequest request(String id,long user){ReserveRequest r=new ReserveRequest();r.setRequestId(id);r.setUserId(user);r.setEventSessionId("session");r.setTicketSkuId("sku");r.setDeviceId("device"+user);r.setQuantity(1);return r;}
 void block(AtomicReference<String> thread){when(backend.submit(any(),anyString())).thenAnswer(a->{thread.set(Thread.currentThread().getName());started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}return Collections.emptyMap();});}
 @Test void receiptDoesNotWaitForBackendAndNeverRunsOnCaller() throws Exception {
  AtomicReference<String> thread=new AtomicReference<>();block(thread);ingress=new ReservationIngress(backend,intents,metrics,env);
  Map<String,Object> receipt=ingress.receive(request("one",1),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));
  assertEquals(false,receipt.get("reliableAccepted"));assertEquals(false,receipt.get("reservationSuccess"));assertEquals(true,receipt.get("newSubmission"));assertTrue(thread.get().startsWith("sale-ingress-"));assertNotEquals(Thread.currentThread().getName(),thread.get());
  verify(backend,never()).lookupRequest(anyString(),anyLong());
 }
 @Test void fullQueueRejectsWithoutCallerRunsOrFalseReceiptAndDuplicatesAreCoalesced() throws Exception {
  block(new AtomicReference<>());ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("one",1),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));
  ingress.receive(request("two",2),"ip");assertEquals(false,ingress.receive(request("two",2),"ip").get("newSubmission"));
  assertEquals(429,assertThrows(BusinessException.class,()->ingress.receive(request("three",3),"ip")).status);
  assertEquals(2,metrics.counter("reservation.ingress.submitted").count());verify(backend,times(1)).submit(any(),anyString());
  assertEquals(403,assertThrows(BusinessException.class,()->ingress.receive(request("two",9),"ip")).status);
  ReserveRequest changed=request("two",2);changed.setQuantity(2);assertEquals(409,assertThrows(BusinessException.class,()->ingress.receive(changed,"ip")).status);
 }
 @Test void snapshotsMutableInputBeforeItWaitsInQueue() throws Exception {
  block(new AtomicReference<>());ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("one",1),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));
  ReserveRequest r=request("two",2);ingress.receive(r,"ip");r.setQuantity(99);release.countDown();
  verify(backend,timeout(2000)).submit(argThat(x->x.getRequestId().equals("two")&&x.getQuantity()==1),eq("ip"));
 }
 @Test void missingOnAnotherInstanceIsUnconfirmedNotFailureAndWaitIsFinite(){
  ingress=new ReservationIngress(backend,intents,metrics,env);Map<String,Object> pending=ingress.status("other-node",1,System.currentTimeMillis());
  assertEquals("UNKNOWN",pending.get("state"));assertEquals(false,pending.get("terminal"));assertEquals(false,pending.get("authoritative"));
  Map<String,Object> expired=ingress.status("other-node",1,System.currentTimeMillis()-130000);assertEquals("UNCONFIRMED",expired.get("state"));assertEquals(false,expired.get("pollingSuggested"));assertEquals(false,expired.get("terminal"));
 }
 @Test void durableResultWinsAcrossInstancesAndAuthorizationIsPreserved(){
  ingress=new ReservationIngress(backend,intents,metrics,env);Map<String,Object> result=new HashMap<>();result.put("state","RESERVED");result.put("reservationSuccess",true);result.put("orderId","order");when(backend.lookupRequest("durable",1)).thenReturn(result);
  Map<String,Object> status=ingress.status("durable",1,0L);assertEquals(true,status.get("authoritative"));assertEquals(true,status.get("reservationSuccess"));assertEquals(true,status.get("terminal"));
  when(backend.lookupRequest("durable",2)).thenThrow(new BusinessException(403,"denied"));assertEquals(403,assertThrows(BusinessException.class,()->ingress.status("durable",2,null)).status);
 }
 @Test void unavailableDatabaseDoesNotFabricateFailure(){
  ingress=new ReservationIngress(backend,intents,metrics,env);when(backend.lookupRequest(anyString(),anyLong())).thenThrow(new IllegalStateException("offline"));Map<String,Object> state=ingress.status("request",1,0L);assertEquals("STATUS_UNAVAILABLE",state.get("state"));assertEquals(false,state.get("terminal"));assertEquals(false,state.get("pollingSuggested"));
 }
 @Test void anotherUsersVolatileIdCannotShadowTheDurableOwner() throws Exception {
  block(new AtomicReference<>());ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("collision",9),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));
  assertEquals("UNKNOWN",ingress.status("collision",7,System.currentTimeMillis()).get("state"));
  Map<String,Object> durable=new HashMap<>();durable.put("state","RESERVED");durable.put("reservationSuccess",true);durable.put("orderId","owned-by-seven");when(backend.lookupRequest("collision",7)).thenReturn(durable);
  assertEquals("owned-by-seven",ingress.status("collision",7,System.currentTimeMillis()).get("orderId"));
  when(backend.lookupRequest("collision",9)).thenThrow(new BusinessException(403,"durable owner differs"));assertEquals(403,assertThrows(BusinessException.class,()->ingress.status("collision",9,null)).status);
 }
 @Test void expiredQueueEntryNeverInvokesBackendAndFreesCapacity() throws Exception {
  env.withProperty("ticketing.ingress.wait-ms","100");block(new AtomicReference<>());ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("one",1),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));ingress.receive(request("two",2),"ip");Thread.sleep(130);ingress.reap();
  assertEquals(0,metrics.get("reservation.ingress.queue.depth").gauge().value());verify(backend,never()).submit(argThat(r->r.getRequestId().equals("two")),anyString());assertEquals(true,ingress.receive(request("two",2),"ip").get("newSubmission"));
 }
 @Test void rateAndUserBoundsHaveExplicitRejection(){
  env.withProperty("ticketing.ingress.post-rate","1");ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("one",1),"ip");assertEquals(429,assertThrows(BusinessException.class,()->ingress.receive(request("two",2),"ip")).status);assertEquals(1,metrics.counter("reservation.ingress.rejected","reason","rate_limited").count());
 }
 @Test void shutdownDoesNotPretendQueuedWorkWasDurable() throws Exception {
  block(new AtomicReference<>());ingress=new ReservationIngress(backend,intents,metrics,env);ingress.receive(request("one",1),"ip");assertTrue(started.await(2,TimeUnit.SECONDS));ingress.receive(request("two",2),"ip");ingress.close();assertEquals(1,metrics.counter("reservation.ingress.shutdown.discarded").count());assertEquals(503,assertThrows(BusinessException.class,()->ingress.receive(request("three",3),"ip")).status);
 }
}

package com.ticketing.ticket;
import com.ticketing.common.BusinessException;
import com.ticketing.common.context.UserHolder;
import com.ticketing.common.dto.UserDTO;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReservationIngressControllerTest {
 ReservationV2Service backend=mock(ReservationV2Service.class);ReservationIngress ingress=mock(ReservationIngress.class);
 ReservationV2Controller controller=new ReservationV2Controller(backend,ingress);
 @BeforeEach void user(){UserDTO user=new UserDTO();user.setId(7L);UserHolder.saveUser(user);}
 @AfterEach void clear(){UserHolder.removeUser();}
 @Test void postReturns202OnlyAfterActualSubmissionAndNeverInvokesDurableBackend(){
  ReserveRequest r=new ReserveRequest();r.setUserId(7L);Map<String,Object> receipt=Collections.singletonMap("newSubmission",true);when(ingress.receive(eq(r),anyString())).thenReturn(receipt);
  assertEquals(202,controller.create(r,new MockHttpServletRequest()).getStatusCodeValue());verify(ingress).receive(eq(r),anyString());verifyNoInteractions(backend);
 }
 @Test void rejectionCannotBeTurnedIntoSuccessfulReceipt(){
  ReserveRequest r=new ReserveRequest();r.setUserId(7L);when(ingress.receive(eq(r),anyString())).thenThrow(new BusinessException(429,"full"));assertEquals(429,assertThrows(BusinessException.class,()->controller.create(r,new MockHttpServletRequest())).status);verifyNoInteractions(backend);
 }
 @Test void identityMismatchNeverSubmits(){ReserveRequest r=new ReserveRequest();r.setUserId(8L);assertEquals(403,assertThrows(BusinessException.class,()->controller.create(r,new MockHttpServletRequest())).status);verifyNoInteractions(ingress,backend);}
}

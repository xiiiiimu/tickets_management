package com.ticketing.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Acceptance capacity: worker prediction, observed drain while saturated, hard capacity and a floor of one active round. */
class V3CapacityTest {
    int cap(int predicted,double drain,int active,int activeLimit,int hard){
        ReservationAttemptStore store=mock(ReservationAttemptStore.class);when(store.predictedWaitingLimit(activeLimit,active)).thenReturn(predicted);
        V3StreamWorker w=mock(V3StreamWorker.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(w,"attempts",store);ReflectionTestUtils.setField(w,"drainPerSecond",drain);ReflectionTestUtils.setField(w,"lastActive",active);
        return w.lastCap(activeLimit,hard);
    }
    @Test void withoutSaturationTheWorkerPredictionApplies(){assertEquals(240,cap(240,0,0,16,512));}
    @Test void observedDrainWhileSaturatedLimitsAdmissionToAboutFourSeconds(){assertEquals((int)Math.ceil(20*4.0)+16,cap(240,20,16,16,512),"20/s drain -> 96, not 240");}
    @Test void hardCapacityMinusActiveIsNeverExceeded(){assertEquals(12,cap(240,0,500,16,512));}
    @Test void floorIsOneRoundOfActiveSlotsUnlessHardCapacityForbidsIt(){assertEquals(16,cap(0,0.1,0,16,512));assertEquals(0,cap(0,0,512,16,512));}
}

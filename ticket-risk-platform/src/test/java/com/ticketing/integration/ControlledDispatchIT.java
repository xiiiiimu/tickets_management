package com.ticketing.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Select only the four locking/compensation methods in the controlled runner. */
@TestPropertySource(properties="ticketing.reservations-v2.dispatch.batch-claim=true")
class ControlledDispatchIT extends ReservationV2IT {
    @BeforeEach void candidateEnabled(){assertEquals(Boolean.TRUE,ReflectionTestUtils.getField(service,"batchClaim"));}
}

package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.sale.SaleManifest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptRiskAdmissionTest {
    AttemptRiskProfiles profiles;ReviewQuickCheck quick;AttemptRiskAdmission service;Map<String,Object> job;SaleManifest plan;
    @BeforeEach void setup(){profiles=mock(AttemptRiskProfiles.class);quick=mock(ReviewQuickCheck.class);service=new AttemptRiskAdmission(profiles,quick,new SimpleMeterRegistry());job=new HashMap<>();job.put("user_id",7L);job.put("device_id","dev");job.put("ip","ip");plan=new SaleManifest("{\"version\":1,\"rules\":[{\"ruleCode\":\"DECISION_POLICY\",\"enabled\":true,\"weight\":0,\"version\":1,\"config\":{\"rateLimit\":180,\"review\":50,\"block\":100}}]}",new ObjectMapper());}
    void profile(String value){doReturn(new AttemptRiskProfiles.Snapshot(value,"version",Long.MAX_VALUE,0)).when(profiles).read(7,"dev","ip");}
    @Test void passUsesCommonChecksButNotQuickReview(){profile("PASS");Map<String,Object> d=service.classify(job,plan);assertEquals("PASS",d.get("decision"));verify(quick).base(job,180);verify(quick,never()).review(any(),any(),any());}
    @Test void knownBlockWinsBeforeOldAgreeCanBeRead(){profile("BLOCK");assertEquals("BLOCK",service.classify(job,plan).get("decision"));verifyNoInteractions(quick);}
    @Test void reviewAgreeRetainsReviewForExistingPostIssueInvestigation(){profile("REVIEW");when(quick.review(eq(job),eq(plan),any())).thenReturn(new ReviewQuickCheck.Result("AGREE","QUICK_AGREE",true));Map<String,Object> d=service.classify(job,plan);assertEquals("REVIEW",d.get("decision"));assertEquals("AGREE",d.get("quickDecision"));assertEquals(true,d.get("reviewCacheHit"));assertEquals(Collections.emptyMap(),d.get("features"));assertEquals("PROFILE_QUICK_V1",d.get("qualificationMode"));}
    @Test void rejectAffectsOnlyThisAttemptNotLongTermProfile(){profile("REVIEW");when(quick.review(eq(job),eq(plan),any())).thenReturn(new ReviewQuickCheck.Result("REJECT","QUICK_FREQUENCY",false));Map<String,Object> d=service.classify(job,plan);assertEquals("BLOCK",d.get("decision"));assertEquals("REVIEW",d.get("profileDecision"));assertEquals("REJECT",d.get("quickDecision"));assertEquals("RISK_REJECTED",d.get("resultCode"));verify(profiles).read(7,"dev","ip");verifyNoMoreInteractions(profiles);}
    @Test void cacheAgreeCannotBypassCommonLiveBlacklist(){profile("REVIEW");when(quick.base(job,180)).thenReturn("BLACKLIST");assertEquals("BLOCK",service.classify(job,plan).get("decision"));verify(quick,never()).review(any(),any(),any());}
    @Test void unavailableProfileOrReviewNeverBecomesAllowedDecision(){when(profiles.read(7,"dev","ip")).thenThrow(new AttemptRiskAdmission.Unavailable("PROFILE_UNAVAILABLE",null));assertThrows(AttemptRiskAdmission.Unavailable.class,()->service.classify(job,plan));profile("REVIEW");when(quick.review(eq(job),eq(plan),any())).thenThrow(new AttemptRiskAdmission.Unavailable("REVIEW_UNAVAILABLE",null));assertThrows(AttemptRiskAdmission.Unavailable.class,()->service.classify(job,plan));}
    @Test void existingRateThresholdIsNotTruncatedToInteger(){profile("PASS");SaleManifest fractional=new SaleManifest(plan.json().replace("\"rateLimit\":180","\"rateLimit\":180.5"),new ObjectMapper());assertEquals("PASS",service.classify(job,fractional).get("decision"));verify(quick).base(job,180.5);}
}

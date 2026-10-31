package com.ticketing.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.repository.RiskProfileRepository;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptRiskProfilesTest {
    RiskProfileRepository repository;StringRedisTemplate redis;ValueOperations<String,String> values;AttemptRiskProfiles reader;
    long now=1800000000000L;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){repository=mock(RiskProfileRepository.class);redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);reader=new AttemptRiskProfiles(repository,redis,new ObjectMapper());reader.clock(()->now);when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.emptyList());}
    RiskProfile profile(String type,String value,String decision,int version,long expires){RiskProfile p=new RiskProfile();p.setId(type+value);p.setSubjectType(type);p.setSubjectValue(value);p.setDecision(decision);p.setVersion(version);p.setExpiresAt(new Timestamp(expires));return p;}
    @Test void cacheMissAndMysqlConfirmedMissingIsTemporaryReviewWithoutWritingPortrait(){assertEquals("REVIEW",reader.read(7,"dev","ip").decision);verify(repository).findSubjects(7,"dev","ip");verifyNoMoreInteractions(repository);}
    @Test void mysqlUnavailableDoesNotBecomeMissingReview(){when(repository.findSubjects(anyLong(),anyString(),anyString())).thenThrow(new IllegalStateException("offline"));assertEquals("PROFILE_UNAVAILABLE",assertThrows(AttemptRiskAdmission.Unavailable.class,()->reader.read(7,"dev","ip")).code);}
    @Test void redisReadExceptionDoesNotBecomeCacheMiss(){when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));assertThrows(AttemptRiskAdmission.Unavailable.class,()->reader.read(7,"dev","ip"));verifyNoInteractions(repository);}
    @Test void expiredFormalPortraitIsUnavailableInsteadOfMissing(){when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(profile("USER","7","PASS",1,now)));assertThrows(AttemptRiskAdmission.Unavailable.class,()->reader.read(7,"dev","ip"));}
    @Test void cachedPassCannotMaskLatestFormalBlock(){when(values.get(RiskProfileCache.key("USER","7"))).thenReturn("{\"decision\":\"PASS\",\"version\":1}");when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(profile("USER","7","BLOCK",2,now+10000)));assertEquals("BLOCK",reader.read(7,"dev","ip").decision);}
    @Test void deviceBlockBeatsMissingUserAndExpiredOtherPortrait(){when(repository.findSubjects(7,"dev","ip")).thenReturn(Arrays.asList(profile("DEVICE","dev","BLOCK",1,now+10000),profile("IP","ip","PASS",1,now-1)));assertEquals("BLOCK",reader.read(7,"dev","ip").decision);}
    @Test void passRequiresFormalUserPassAndReviewOverridesPass(){RiskProfile user=profile("USER","7","PASS",1,now+60000);when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(user));assertEquals("PASS",reader.read(7,"dev","ip").decision);when(repository.findSubjects(7,"dev","ip")).thenReturn(Arrays.asList(user,profile("DEVICE","dev","REVIEW",1,now+10000)));assertEquals("REVIEW",reader.read(7,"dev","ip").decision);}
    @Test void unknownProfileDecisionAndCorruptCacheAreNotAllowed(){when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(profile("USER","7","UNKNOWN",1,now+10000)));assertThrows(AttemptRiskAdmission.Unavailable.class,()->reader.read(7,"dev","ip"));when(values.get(anyString())).thenReturn("not-json");assertThrows(AttemptRiskAdmission.Unavailable.class,()->reader.read(7,"dev","ip"));}
    @Test void versionVectorChangesAndExpiryIsBoundedByEarliestFormalProfile(){RiskProfile user=profile("USER","7","REVIEW",1,now+60000);when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(user));String old=reader.read(7,"dev","ip").version;user.setVersion(2);assertNotEquals(old,reader.read(7,"dev","ip").version);when(repository.findSubjects(7,"dev","ip")).thenReturn(Arrays.asList(user,profile("IP","ip","PASS",1,now+10000)));assertEquals(now+10000,reader.read(7,"dev","ip").validUntil);}
    @Test void existingNegativeProfileScoreIsNotClampedOrWrittenBack(){RiskProfile user=profile("USER","7","REVIEW",1,now+60000);user.setScore(-12);when(repository.findSubjects(7,"dev","ip")).thenReturn(Collections.singletonList(user));assertEquals(-12,reader.read(7,"dev","ip").score);verify(repository).findSubjects(7,"dev","ip");verifyNoMoreInteractions(repository);}
}

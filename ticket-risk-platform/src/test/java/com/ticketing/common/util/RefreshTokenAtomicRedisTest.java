package com.ticketing.common.util;

import com.ticketing.common.context.UserHolder;
import com.ticketing.common.dto.UserDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerInterceptor;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only a fresh owned Redis DB. No application context, MySQL or transaction tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named="token.refresh.tests",matches="true")
class RefreshTokenAtomicRedisTest {
 StringRedisTemplate redis,other;LettuceConnectionFactory factory,otherFactory;
 final Map<String,Class<?>> versions=new LinkedHashMap<>();final List<URLClassLoader> loaders=new ArrayList<>();
 @BeforeAll void setup()throws Exception{
  int db=Integer.parseInt(System.getenv("TOKEN_TEST_REDIS_DB"));assertTrue(db>0&&db<256);
  factory=connection(db);otherFactory=connection(db);redis=new StringRedisTemplate(factory);other=new StringRedisTemplate(otherFactory);
  assertEquals(System.getenv("TOKEN_TEST_OWNER"),redis.opsForValue().get("credit-risk:token-refresh-test-owner"));
  for(String version:Arrays.asList("baseline","candidate")){
   Path path=Paths.get(System.getProperty("token.refresh."+version));URLClassLoader loader=new URLClassLoader(new URL[]{path.toUri().toURL()},getClass().getClassLoader()){
    protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
     if(!name.equals("com.ticketing.common.util.RefreshTokenInterceptor"))return super.loadClass(name,resolve);
     Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
    }
   };loaders.add(loader);versions.put(version,loader.loadClass("com.ticketing.common.util.RefreshTokenInterceptor"));
  }
 }
 LettuceConnectionFactory connection(int db){RedisStandaloneConfiguration c=new RedisStandaloneConfiguration("127.0.0.1",26379);c.setDatabase(db);c.setPassword(System.getenv("TOKEN_TEST_REDIS_PASSWORD"));LettuceConnectionFactory f=new LettuceConnectionFactory(c);f.afterPropertiesSet();return f;}
 @AfterAll void close()throws Exception{UserHolder.removeUser();if(factory!=null)factory.destroy();if(otherFactory!=null)otherFactory.destroy();for(URLClassLoader l:loaders)l.close();}
 HandlerInterceptor interceptor(String v,StringRedisTemplate template)throws Exception{return (HandlerInterceptor)versions.get(v).getConstructor(StringRedisTemplate.class).newInstance(template);}
 static class Outcome {UserDTO user;boolean admitted;String error;}
 Outcome call(HandlerInterceptor i,String token)throws Exception{
  MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/tickets/reservations");if(token!=null)request.addHeader("authorization",token);MockHttpServletResponse response=new MockHttpServletResponse();Outcome o=new Outcome();
  try{i.preHandle(request,response,new Object());o.user=UserHolder.getUser();o.admitted=new LoginInterceptor().preHandle(request,response,new Object());}catch(RuntimeException e){o.error=e.getClass().getName();o.user=UserHolder.getUser();}
  finally{i.afterCompletion(request,response,new Object(),null);assertNull(UserHolder.getUser());}return o;
 }
 Map<String,String> hash(String id){Map<String,String> h=new LinkedHashMap<>();if(id!=null)h.put("id",id);h.put("role","USER");h.put("nickName","same-user");return h;}
 String seed(Map<String,String> h){String token="atomic-"+UUID.randomUUID();redis.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,h);if(!h.isEmpty())redis.expire(RedisConstants.LOGIN_USER_KEY+token,60,TimeUnit.SECONDS);return token;}
 void equalSemantics(Map<String,String> h)throws Exception{
  Outcome before=null;for(String v:versions.keySet()){
   String token=seed(h);Outcome actual=call(interceptor(v,redis),token);long ttl=redis.getExpire(RedisConstants.LOGIN_USER_KEY+token,TimeUnit.MILLISECONDS);
   if(actual.error!=null)assertTrue(ttl>0&&ttl<=60000,"Conversion errors must not refresh TTL: "+v);
   else if(actual.user!=null)assertTrue(ttl>1780000&&ttl<=1800000,"Sliding TTL must remain 30 minutes: "+v);
   else assertEquals(-2,ttl);
   if(before!=null){assertEquals(before.admitted,actual.admitted);assertEquals(before.user,actual.user);assertEquals(before.error,actual.error);}before=actual;
  }
 }
 @Test void validTokenHasSameIdentityAndThirtyMinuteSlidingTtl()throws Exception{equalSemantics(hash("123"));}
 @Test void optionalFieldsMayBeMissingExactlyAsBefore()throws Exception{equalSemantics(Collections.singletonMap("id","123"));}
 @Test void missingIdKeepsLegacyNonemptyHashBehavior()throws Exception{equalSemantics(hash(null));}
 @Test void emptyIdKeepsLegacyConversionAndRenewal()throws Exception{equalSemantics(hash(""));}
 @Test void garbageIdThrowsWithoutRenewal()throws Exception{equalSemantics(hash("garbage"));}
 @Test void exoticNumericFormsKeepHutoolConversion()throws Exception{for(String value:Arrays.asList(" 123 ","+123","-1","0","1.5","1e3","1abc","0xff","NaN","9223372036854775808","99999999999999999999999"))equalSemantics(hash(value));}
 @Test void customAndAliasFieldsKeepOriginalMapping()throws Exception{Map<String,String> h=hash("123");h.put("nick_name","alias");h.put("custom","ignored");equalSemantics(h);Map<String,String> alias=new LinkedHashMap<>();alias.put("Id","garbage");alias.put("role","USER");equalSemantics(alias);}
 @Test void blankAndNonexistentTokensNeverAuthenticateOrCreateKeys()throws Exception{
  for(String v:versions.keySet())for(String token:Arrays.asList(null,"","  ","not-issued-"+UUID.randomUUID())){Outcome o=call(interceptor(v,redis),token);assertFalse(o.admitted);assertNull(o.user);assertNull(o.error);}
 }
 @Test void expiredTokensStayExpired()throws Exception{
  for(String v:versions.keySet()){String token=seed(hash("123"));redis.expire(RedisConstants.LOGIN_USER_KEY+token,1,TimeUnit.MILLISECONDS);Thread.sleep(25);Outcome o=call(interceptor(v,redis),token);assertFalse(o.admitted);assertNull(o.user);assertFalse(redis.hasKey(RedisConstants.LOGIN_USER_KEY+token));}
 }
 @Test void deletionRevokesOnBothInstancesAndNeverResurrects()throws Exception{
  for(String v:versions.keySet()){String token=seed(hash("123"));assertTrue(call(interceptor(v,redis),token).admitted);redis.delete(RedisConstants.LOGIN_USER_KEY+token);for(StringRedisTemplate node:Arrays.asList(redis,other)){assertFalse(call(interceptor(v,node),token).admitted);assertFalse(redis.hasKey(RedisConstants.LOGIN_USER_KEY+token));}}
 }
 @Test void persistentLegacyTokensStillReceiveTheSameTtl()throws Exception{
  for(String v:versions.keySet()){String token=seed(hash("123"));redis.persist(RedisConstants.LOGIN_USER_KEY+token);assertTrue(call(interceptor(v,redis),token).admitted);assertTrue(redis.getExpire(RedisConstants.LOGIN_USER_KEY+token,TimeUnit.SECONDS)>1780);}
 }
 @Test void wrongRedisTypeFailsWithoutRenewingOrGrantingIdentity()throws Exception{
  for(String v:versions.keySet()){String token="wrong-type-"+UUID.randomUUID();String key=RedisConstants.LOGIN_USER_KEY+token;redis.opsForValue().set(key,"bad",60,TimeUnit.SECONDS);Outcome o=call(interceptor(v,redis),token);assertNotNull(o.error);assertNull(o.user);assertTrue(redis.getExpire(key,TimeUnit.SECONDS)<=60);}
 }
 @Test void standardCandidateUsesOneCallbackAndNoSeparateExpire()throws Exception{
  String token=seed(hash("123"));StringRedisTemplate spy=spy(redis);assertTrue(call(interceptor("candidate",spy),token).admitted);
  verify(spy,times(1)).execute(org.mockito.ArgumentMatchers.<RedisCallback<List<?>>>any());verify(spy,never()).expire(anyString(),anyLong(),any(TimeUnit.class));
 }
 @Test void concurrentRequestsAndPostLogoutRequestsAreSafeAcrossInstances()throws Exception{
  for(String v:versions.keySet()){
   String token=seed(hash("123"));HandlerInterceptor a=interceptor(v,redis),b=interceptor(v,other);ExecutorService pool=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());
   try{
    List<Future<Outcome>> calls=new ArrayList<>();for(int i=0;i<64;i++){final HandlerInterceptor node=i%2==0?a:b;calls.add(pool.submit(()->call(node,token)));}
    for(Future<Outcome> f:calls){Outcome o=f.get(15,TimeUnit.SECONDS);assertTrue(o.admitted);assertEquals(123L,o.user.getId().longValue());}
    long ttl=redis.getExpire(RedisConstants.LOGIN_USER_KEY+token,TimeUnit.MILLISECONDS);assertTrue(ttl>1780000&&ttl<=1800000);
    redis.delete(RedisConstants.LOGIN_USER_KEY+token);calls.clear();for(int i=0;i<32;i++){final HandlerInterceptor node=i%2==0?a:b;calls.add(pool.submit(()->call(node,token)));}for(Future<Outcome> f:calls)assertFalse(f.get(15,TimeUnit.SECONDS).admitted);assertFalse(redis.hasKey(RedisConstants.LOGIN_USER_KEY+token));
   }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
  }
 }
}

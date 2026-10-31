package com.ticketing.sale;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SaleManifestCacheTest {
    final ObjectMapper json=new ObjectMapper();
    String payload(String session,long version){return "{\"sessionId\":\""+session+"\",\"version\":"+version+",\"bucketCount\":16,\"skus\":[],\"rules\":[]}";}
    @Test void cacheDoesNotMaskMissingTamperedOrWrongVersionRedis(){
        SaleManifestCache cache=new SaleManifestCache(2);String bytes=payload("s",1);String digest=new SaleManifest(bytes,json).digest();
        SaleManifest first=cache.get("s",1,digest,bytes,json);
        assertSame(first,cache.get("s",1,digest,bytes,json));
        assertThrows(BusinessException.class,()->cache.get("s",1,digest,null,json));
        assertThrows(BusinessException.class,()->cache.get("s",1,digest,bytes+" ",json));
        assertThrows(BusinessException.class,()->cache.get("s",2,digest,bytes,json));
        assertThrows(BusinessException.class,()->cache.get("s",1,"bad",bytes,json));
    }
    @Test void memoryIsBoundedAcrossVersions(){
        SaleManifestCache cache=new SaleManifestCache(2);
        for(int i=0;i<10;i++){String bytes=payload("s",i);cache.get("s",i,new SaleManifest(bytes,json).digest(),bytes,json);}
        assertEquals(2,cache.size());
    }
}

package com.ticketing.sale;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.risk.RiskRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable published bytes plus a detached, request-local rule list. */
public final class SaleManifest {
    private final String json, digest;
    private final Map<String,Object> data;
    public SaleManifest(String json, ObjectMapper mapper) {
        try {
            this.json=json;
            this.data=freezeMap(mapper.readValue(json,new TypeReference<Map<String,Object>>(){}));
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();char[] hex="0123456789abcdef".toCharArray();
            for(byte b:hash){value.append(hex[(b & 255)>>>4]);value.append(hex[b & 15]);}
            this.digest=value.toString();
        } catch(Exception error){throw new IllegalArgumentException("Invalid sale manifest",error);}
    }
    public String json(){return json;} public String digest(){return digest;}
    public long version(){return ((Number)data.get("version")).longValue();}
    public String sessionId(){return (String)data.get("sessionId");}
    public int bucketCount(){return ((Number)data.get("bucketCount")).intValue();}
    @SuppressWarnings("unchecked") public List<RiskRule> rules(){
        List<RiskRule> result=new ArrayList<>();
        for(Map<String,Object> row:(List<Map<String,Object>>)data.get("rules")){
            RiskRule rule=new RiskRule();rule.setRuleCode((String)row.get("ruleCode"));
            rule.setEnabled(Boolean.TRUE.equals(row.get("enabled")));rule.setWeight(((Number)row.get("weight")).intValue());
            rule.setVersion(((Number)row.get("version")).intValue());
            rule.setConfig(Collections.unmodifiableMap(new LinkedHashMap<>((Map<String,Object>)row.get("config"))));result.add(rule);
        }
        return Collections.unmodifiableList(result);
    }
    @SuppressWarnings("unchecked") public Map<String,Object> sku(String id){
        for(Map<String,Object> sku:(List<Map<String,Object>>)data.get("skus"))
            if(id.equals(sku.get("id")))return Collections.unmodifiableMap(sku);
        return null;
    }
    @SuppressWarnings("unchecked") public List<String> skuIds(){
        List<String> ids=new ArrayList<>();for(Map<String,Object> sku:(List<Map<String,Object>>)data.get("skus"))ids.add((String)sku.get("id"));
        return Collections.unmodifiableList(ids);
    }
    private static Map<String,Object> freezeMap(Map<String,Object> input){
        Map<String,Object> result=new LinkedHashMap<>();
        input.forEach((key,value)->result.put(key,freeze(value)));return Collections.unmodifiableMap(result);
    }
    @SuppressWarnings("unchecked") private static Object freeze(Object value){
        if(value instanceof Map)return freezeMap((Map<String,Object>)value);
        if(value instanceof List){List<Object> copy=new ArrayList<>();for(Object item:(List<?>)value)copy.add(freeze(item));return Collections.unmodifiableList(copy);}
        return value;
    }
}

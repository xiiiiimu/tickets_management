package com.ticketing.infrastructure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Component
public class Db {
 public final JdbcTemplate jdbc; private final ObjectMapper mapper;
 public Db(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
 public String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
 public static String id(){return UUID.randomUUID().toString();}
 public static long number(Map<String,Object> row,String key){Object v=row.get(key);return v==null?0:((Number)v).longValue();}
}

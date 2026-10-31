package com.ticketing.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
    @Select("SELECT "
            + "COUNT(*) AS audit_records, "
            + "SUM(IF(call_type = 'LLM', 1, 0)) AS llm_audit_records, "
            + "SUM(IF(call_type = 'LLM' AND IFNULL(token_count, 0) > 0, 1, 0)) AS llm_real_api_calls, "
            + "SUM(IF(call_type = 'LLM' AND cache_hit = 1, 1, 0)) AS llm_cache_hit_calls, "
            + "SUM(IF(call_type = 'LLM', IFNULL(token_count, 0), 0)) AS llm_total_tokens, "
            + "SUM(IF(call_type = 'LLM' AND success = 0, 1, 0)) AS llm_failed_calls "
            + "FROM tb_audit_log")
    Map<String, Object> summarizeLlmUsage();

    @Select("SELECT "
            + "node_name AS nodeName, "
            + "SUM(1) AS auditRecords, "
            + "SUM(IF(IFNULL(token_count, 0) > 0, 1, 0)) AS realApiCalls, "
            + "SUM(IF(cache_hit = 1, 1, 0)) AS cacheHitCalls, "
            + "SUM(IFNULL(token_count, 0)) AS totalTokens, "
            + "ROUND(AVG(CASE WHEN IFNULL(token_count, 0) > 0 THEN token_count END), 2) AS avgTokensPerRealCall "
            + "FROM tb_audit_log "
            + "WHERE call_type = 'LLM' "
            + "GROUP BY node_name "
            + "ORDER BY totalTokens DESC, auditRecords DESC")
    List<Map<String, Object>> summarizeLlmUsageByNode();
}

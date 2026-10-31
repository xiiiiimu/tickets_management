package com.ticketing.audit.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ticketing.audit.entity.AuditLog;
import com.ticketing.audit.mapper.AuditLogMapper;
import com.ticketing.audit.metrics.WorkflowMetrics;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    public static final String CALL_LLM = "LLM";
    public static final String CALL_TOOL = "TOOL";

    @Resource
    private AuditLogMapper auditLogMapper;
    @Resource
    private WorkflowMetrics workflowMetrics;

    public AuditLog save(Map<String, Object> args) {
        AuditLog row = new AuditLog();
        row.setWorkflowId(stringVal(args, "workflowId"));
        row.setTraceId(stringVal(args, "traceId"));
        row.setNodeName(stringVal(args, "nodeName"));
        row.setCallType(stringVal(args, "callType", CALL_TOOL));
        row.setPromptVersion(intVal(args, "promptVersion"));
        row.setRuleVersion(stringVal(args, "ruleVersion"));
        row.setRequestJson(truncateJson(args.get("request")));
        row.setResponseJson(truncateJson(args.get("response")));
        row.setTokenCount(intVal(args, "tokenCount", 0));
        row.setCostTimeMs(intVal(args, "costTimeMs", 0));
        row.setSuccess(boolVal(args, "success", true));
        row.setCacheHit(boolVal(args, "cacheHit", false));
        row.setErrorMsg(stringVal(args, "errorMsg"));
        auditLogMapper.insert(row);

        long cost = row.getCostTimeMs() != null ? row.getCostTimeMs() : 0;
        boolean ok = Boolean.TRUE.equals(row.getSuccess());
        if (CALL_LLM.equalsIgnoreCase(row.getCallType())) {
            workflowMetrics.recordLlm(row.getNodeName(), safeInt(row.getTokenCount()), cost, ok);
            if (Boolean.TRUE.equals(row.getCacheHit())) {
                workflowMetrics.recordLlmCacheHit(row.getNodeName());
            }
        } else {
            workflowMetrics.recordToolAudit(row.getNodeName(), cost, ok);
        }
        return row;
    }

    public Map<String, Object> summarizeLlmUsage() {
        Map<String, Object> raw = auditLogMapper.summarizeLlmUsage();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("auditRecords", longVal(raw, "llm_audit_records"));
        summary.put("realApiCalls", longVal(raw, "llm_real_api_calls"));
        summary.put("cacheHitCalls", longVal(raw, "llm_cache_hit_calls"));
        summary.put("totalTokens", longVal(raw, "llm_total_tokens"));
        summary.put("failedCalls", longVal(raw, "llm_failed_calls"));
        long realApiCalls = longVal(raw, "llm_real_api_calls");
        long totalTokens = longVal(raw, "llm_total_tokens");
        summary.put("avgTokensPerRealCall", realApiCalls > 0 ? (double) totalTokens / realApiCalls : null);
        return summary;
    }

    public List<Map<String, Object>> summarizeLlmUsageByNode() {
        return auditLogMapper.summarizeLlmUsageByNode();
    }

    public List<AuditLog> listByWorkflowId(String workflowId) {
        return auditLogMapper.selectList(
                new QueryWrapper<AuditLog>()
                        .eq("workflow_id", workflowId)
                        .orderByAsc("id"));
    }

    private String truncateJson(Object value) {
        if (value == null) {
            return null;
        }
        String json = value instanceof String ? (String) value : JSONUtil.toJsonStr(value);
        if (json.length() <= 8000) {
            return json;
        }
        return json.substring(0, 8000) + "...[truncated]";
    }

    private String stringVal(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return null;
        }
        return args.get(key).toString();
    }

    private String stringVal(Map<String, Object> args, String key, String defaultValue) {
        String v = stringVal(args, key);
        return v != null ? v : defaultValue;
    }

    private Integer intVal(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return null;
        }
        return Integer.valueOf(args.get(key).toString());
    }

    private int intVal(Map<String, Object> args, String key, int defaultValue) {
        Integer v = intVal(args, key);
        return v != null ? v : defaultValue;
    }

    private boolean boolVal(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || args.get(key) == null) {
            return defaultValue;
        }
        Object raw = args.get(key);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        return Boolean.parseBoolean(raw.toString());
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private long longVal(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return 0L;
        }
        Object raw = args.get(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        return Long.parseLong(raw.toString());
    }
}

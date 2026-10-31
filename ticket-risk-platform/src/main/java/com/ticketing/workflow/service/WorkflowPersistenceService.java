package com.ticketing.workflow.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ticketing.workflow.entity.WorkflowCheckpoint;
import com.ticketing.workflow.entity.WorkflowNodeRecord;
import com.ticketing.workflow.entity.WorkflowRecord;
import com.ticketing.workflow.enums.WorkflowNodeStatus;
import com.ticketing.workflow.enums.WorkflowStatus;
import com.ticketing.workflow.mapper.WorkflowCheckpointMapper;
import com.ticketing.workflow.mapper.WorkflowMapper;
import com.ticketing.workflow.mapper.WorkflowNodeMapper;
import com.ticketing.workflow.trace.WorkflowTraceLogger;
import com.ticketing.audit.metrics.WorkflowMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WorkflowPersistenceService {

    @Resource
    private WorkflowMapper workflowMapper;
    @Resource
    private WorkflowNodeMapper workflowNodeMapper;
    @Resource
    private WorkflowCheckpointMapper workflowCheckpointMapper;
    @Resource
    private WorkflowTraceLogger workflowTraceLogger;
    @Resource
    private WorkflowMetrics workflowMetrics;

    @Transactional(rollbackFor = Exception.class)
    public WorkflowRecord initWorkflowIfAbsent(String workflowId, String traceId, Long taskId, String orderId) {
        WorkflowRecord existing = findWorkflow(workflowId);
        if (existing != null) {
            return existing;
        }
        WorkflowRecord row = new WorkflowRecord();
        row.setWorkflowId(workflowId);
        row.setStatus(WorkflowStatus.INIT);
        row.setRetryCount(0);
        row.setTraceId(traceId);
        row.setTaskId(taskId);
        row.setOrderId(orderId);
        workflowMapper.insert(row);
        workflowTraceLogger.info(workflowId, traceId, null, null, 0, 0,
                "workflow init taskId=" + taskId);
        return row;
    }

    public boolean tryAcquireRunning(String workflowId) {
        if (workflowId == null || workflowId.trim().isEmpty()) {
            return false;
        }
        return workflowMapper.casAcquireRunning(workflowId.trim()) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowRecord startWorkflow(String workflowId, String traceId, Long taskId, String orderId) {
        WorkflowRecord existing = findWorkflow(workflowId);
        if (existing != null) {
            if (WorkflowStatus.INIT.equals(existing.getStatus())
                    || WorkflowStatus.FAILED.equals(existing.getStatus())) {
                tryAcquireRunning(workflowId);
                existing = findWorkflow(workflowId);
            }
            existing.setTraceId(traceId);
            existing.setTaskId(taskId);
            existing.setOrderId(orderId);
            workflowMapper.updateById(existing);
            return existing;
        }
        WorkflowRecord row = new WorkflowRecord();
        row.setWorkflowId(workflowId);
        row.setStatus(WorkflowStatus.RUNNING);
        row.setRetryCount(0);
        row.setTraceId(traceId);
        row.setTaskId(taskId);
        row.setOrderId(orderId);
        workflowMapper.insert(row);
        workflowTraceLogger.info(workflowId, traceId, null, null, 0, 0,
                "workflow started taskId=" + taskId);
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowNodeRecord beginNode(String workflowId, String nodeName, String agentName,
                                        String traceId, Object input) {
        updateWorkflowCurrent(workflowId, nodeName);
        WorkflowNodeRecord row = new WorkflowNodeRecord();
        row.setWorkflowId(workflowId);
        row.setNodeName(nodeName);
        row.setAgentName(agentName);
        row.setStatus(WorkflowNodeStatus.RUNNING);
        row.setInputJson(input != null ? JSONUtil.toJsonStr(input) : null);
        row.setRetryCount(0);
        row.setStartTime(LocalDateTime.now());
        workflowNodeMapper.insert(row);
        workflowTraceLogger.info(workflowId, traceId, nodeName, agentName, 0, 0,
                "node begin");
        return row;
    }
    /** Immutable past evidence; does not overwrite current_node, checkpoint, or workflow terminal status. */
    @Transactional(rollbackFor = Exception.class)
    public void appendEvidenceNode(String workflowId,String nodeName,String traceId,Object evidence){
        WorkflowNodeRecord row=new WorkflowNodeRecord();row.setWorkflowId(workflowId);row.setNodeName(nodeName);row.setAgentName("java");
        row.setStatus(WorkflowNodeStatus.SUCCESS);row.setInputJson(JSONUtil.toJsonStr(evidence));row.setOutputJson(JSONUtil.toJsonStr(evidence));
        row.setStartTime(LocalDateTime.now());row.setEndTime(LocalDateTime.now());row.setRetryCount(0);row.setCostTimeMs(0);workflowNodeMapper.insert(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeNode(String workflowId, String nodeName, String traceId, String agentName,
                             boolean success, Object output, String errorCode, String errorMsg,
                             int retryCount, long costTimeMs) {
        WorkflowNodeRecord row = findLatestNode(workflowId, nodeName);
        if (row == null) {
            row = beginNode(workflowId, nodeName, agentName, traceId, null);
        }
        row.setStatus(success ? WorkflowNodeStatus.SUCCESS : WorkflowNodeStatus.FAILED);
        row.setOutputJson(output != null ? JSONUtil.toJsonStr(output) : null);
        row.setErrorCode(errorCode);
        row.setErrorMsg(errorMsg);
        row.setRetryCount(retryCount);
        row.setCostTimeMs((int) costTimeMs);
        row.setEndTime(LocalDateTime.now());
        workflowNodeMapper.updateById(row);

        if (success) {
            workflowTraceLogger.info(workflowId, traceId, nodeName, agentName, retryCount, costTimeMs,
                    "node success");
        } else {
            workflowTraceLogger.error(workflowId, traceId, nodeName, agentName, retryCount, costTimeMs,
                    errorCode, errorMsg, null);
        }
        workflowMetrics.recordNode(nodeName, agentName, success, costTimeMs, retryCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveCheckpoint(String workflowId, String currentNode, Object state, Object history, int retryCount) {
        WorkflowCheckpoint row = workflowCheckpointMapper.selectOne(
                new QueryWrapper<WorkflowCheckpoint>().eq("workflow_id", workflowId).last("LIMIT 1"));
        if (row == null) {
            row = new WorkflowCheckpoint();
            row.setWorkflowId(workflowId);
            row.setCurrentNode(currentNode);
            row.setStateJson(state != null ? JSONUtil.toJsonStr(state) : null);
            row.setHistoryJson(history != null ? JSONUtil.toJsonStr(history) : null);
            row.setRetryCount(retryCount);
            workflowCheckpointMapper.insert(row);
            return;
        }
        row.setCurrentNode(currentNode);
        row.setStateJson(state != null ? JSONUtil.toJsonStr(state) : null);
        row.setHistoryJson(history != null ? JSONUtil.toJsonStr(history) : null);
        row.setRetryCount(retryCount);
        row.setUpdatedAt(LocalDateTime.now());
        workflowCheckpointMapper.updateById(row);
    }

    public Map<String, Object> loadCheckpoint(String workflowId) {
        WorkflowCheckpoint row = workflowCheckpointMapper.selectOne(
                new QueryWrapper<WorkflowCheckpoint>().eq("workflow_id", workflowId).last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("workflowId", row.getWorkflowId());
        data.put("currentNode", row.getCurrentNode());
        data.put("state", row.getStateJson() != null ? JSONUtil.parse(row.getStateJson()) : null);
        data.put("history", row.getHistoryJson() != null ? JSONUtil.parse(row.getHistoryJson()) : null);
        data.put("retryCount", row.getRetryCount());
        data.put("updatedAt", row.getUpdatedAt());
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishWorkflow(String workflowId, String status, String traceId, Object result, int retryCount) {
        WorkflowRecord row = findWorkflow(workflowId);
        if (row == null) {
            return;
        }
        row.setStatus(status);
        row.setRetryCount(retryCount);
        if (result != null) {
            row.setResultJson(JSONUtil.toJsonStr(result));
        }
        workflowMapper.updateById(row);
        workflowTraceLogger.info(workflowId, traceId, row.getCurrentNode(), null, retryCount, 0,
                "workflow finished status=" + status);
        if (WorkflowStatus.MANUAL_REVIEW.equals(status)) {
            workflowMetrics.recordManualReview();
        }
    }

    public Map<String, Object> getExecutionChain(String workflowId) {
        WorkflowRecord workflow = findWorkflow(workflowId);
        if (workflow == null) {
            return null;
        }
        List<WorkflowNodeRecord> nodes = workflowNodeMapper.selectList(
                new QueryWrapper<WorkflowNodeRecord>()
                        .eq("workflow_id", workflowId)
                        .orderByAsc("id"));
        Map<String, Object> checkpoint = loadCheckpoint(workflowId);
        Map<String, Object> data = new HashMap<>();
        data.put("workflow", workflow);
        data.put("nodes", nodes);
        data.put("checkpoint", checkpoint);
        return data;
    }

    public WorkflowRecord findWorkflow(String workflowId) {
        return workflowMapper.selectOne(
                new QueryWrapper<WorkflowRecord>().eq("workflow_id", workflowId).last("LIMIT 1"));
    }

    private WorkflowNodeRecord findLatestNode(String workflowId, String nodeName) {
        return workflowNodeMapper.selectOne(
                new QueryWrapper<WorkflowNodeRecord>()
                        .eq("workflow_id", workflowId)
                        .eq("node_name", nodeName)
                        .orderByDesc("id")
                        .last("LIMIT 1"));
    }

    private void updateWorkflowCurrent(String workflowId, String nodeName) {
        WorkflowRecord row = findWorkflow(workflowId);
        if (row == null) {
            return;
        }
        row.setCurrentNode(nodeName);
        workflowMapper.updateById(row);
    }
}

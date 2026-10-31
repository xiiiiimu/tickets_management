package com.ticketing.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_workflow_node")
public class WorkflowNodeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String nodeName;
    private String agentName;
    private String status;
    private String inputJson;
    private String outputJson;
    private String errorCode;
    private String errorMsg;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer retryCount;
    private Integer costTimeMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

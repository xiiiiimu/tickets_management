package com.ticketing.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_workflow_checkpoint")
public class WorkflowCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String currentNode;
    private String stateJson;
    private String historyJson;
    private Integer retryCount;
    private LocalDateTime updatedAt;
    private LocalDateTime createTime;
}

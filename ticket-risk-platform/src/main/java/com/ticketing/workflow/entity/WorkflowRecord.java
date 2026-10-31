package com.ticketing.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_workflow")
public class WorkflowRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String status;
    private String currentNode;
    private Integer retryCount;
    private String traceId;
    private Long taskId;
    private String orderId;
    private String resultJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

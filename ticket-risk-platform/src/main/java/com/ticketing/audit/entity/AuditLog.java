package com.ticketing.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowId;
    private String traceId;
    private String nodeName;
    private String callType;
    private Integer promptVersion;
    private String ruleVersion;
    private String requestJson;
    private String responseJson;
    private Integer tokenCount;
    private Integer costTimeMs;
    private Boolean success;
    private Boolean cacheHit;
    private String errorMsg;
    private LocalDateTime createTime;
}

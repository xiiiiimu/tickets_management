package com.ticketing.audit.tool;

import com.ticketing.audit.entity.AuditLog;
import com.ticketing.audit.service.AuditLogService;
import com.ticketing.common.Result;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuditToolService {

    @Resource
    private AuditLogService auditLogService;

    public Result saveAuditLog(Map<String, Object> args) {
        AuditLog row = auditLogService.save(args);
        Map<String, Object> data = new HashMap<>(2);
        data.put("id", row.getId());
        return Result.ok(data);
    }
}

package com.ticketing.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.workflow.entity.WorkflowRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowRecord> {

    @Update("UPDATE tb_workflow SET status = 'RUNNING', update_time = NOW() "
            + "WHERE workflow_id = #{workflowId} AND status IN ('INIT', 'FAILED')")
    int casAcquireRunning(@Param("workflowId") String workflowId);
}

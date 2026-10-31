package com.ticketing.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketing.workflow.entity.WorkflowNodeRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNodeRecord> {
}

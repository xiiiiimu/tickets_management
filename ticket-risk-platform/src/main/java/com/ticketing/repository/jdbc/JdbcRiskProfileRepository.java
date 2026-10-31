package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.RiskProfileRepository;
import com.ticketing.risk.RiskProfile;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;

@Repository
public class JdbcRiskProfileRepository implements RiskProfileRepository {

    private final Db db;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.ticketing.risk.RiskSubjectFence fences;

    public JdbcRiskProfileRepository(Db db) {
        this.db = db;
    }

    @Override
    public java.util.List<RiskProfile> findSubjects(long user,String device,String ip){
        java.util.List<RiskProfile> result=new java.util.ArrayList<>();
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT * FROM tb_risk_profile WHERE "
                +"(subject_type='USER' AND subject_value=?) OR (subject_type='DEVICE' AND subject_value=?) OR (subject_type='IP' AND subject_value=?)",
                Long.toString(user),device,ip))result.add(map(row));
        return result;
    }

    @Override
    public RiskProfile findBySubject(String subjectType, String subjectValue) {
        return map(db.one(
                "SELECT * FROM tb_risk_profile WHERE subject_type=? AND subject_value=?",
                subjectType, subjectValue));
    }

    @Override
    public boolean upsert(RiskProfile profile) {
        if(fences!=null)fences.exclusiveOne(profile.getSubjectType(),profile.getSubjectValue());
        RiskProfile existing = findBySubject(profile.getSubjectType(), profile.getSubjectValue());
        if (existing == null) {
            db.jdbc.update(
                    "INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,reason_codes,"
                            + "source_order_id,source_decision_id,version,expires_at) VALUES(?,?,?,?,?,?,?,?,?,0,?)",
                    profile.getId() == null ? Db.id() : profile.getId(),
                    profile.getSubjectType(), profile.getSubjectValue(), profile.getRiskLevel(),
                    profile.getDecision(), profile.getScore(), profile.getReasonCodes(),
                    profile.getSourceOrderId(), profile.getSourceDecisionId(), profile.getExpiresAt());
            return true;
        }
        return db.jdbc.update(
                "UPDATE tb_risk_profile SET risk_level=?,decision=?,score=?,reason_codes=?,source_order_id=?,"
                        + "source_decision_id=?,version=version+1,expires_at=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE subject_type=? AND subject_value=? AND version=?",
                profile.getRiskLevel(), profile.getDecision(), profile.getScore(), profile.getReasonCodes(),
                profile.getSourceOrderId(), profile.getSourceDecisionId(), profile.getExpiresAt(),
                profile.getSubjectType(), profile.getSubjectValue(), existing.getVersion()) == 1;
    }

    @Override
    public boolean upsertFromDecision(String subjectType, String subjectValue, String riskLevel, String decision,
                                      int score, String reasonCodes, String sourceOrderId, String sourceDecisionId,
                                      Timestamp expiresAt) {
        if(fences!=null)fences.exclusiveOne(subjectType,subjectValue);
        RiskProfile existing = findBySubject(subjectType, subjectValue);
        if (existing != null && sourceDecisionId != null && sourceDecisionId.equals(existing.getSourceDecisionId())) {
            return false;
        }
        if (existing == null) {
            db.jdbc.update(
                    "INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,reason_codes,"
                            + "source_order_id,source_decision_id,version,expires_at) VALUES(?,?,?,?,?,?,?,?,?,0,?)",
                    Db.id(), subjectType, subjectValue, riskLevel, decision, score, reasonCodes,
                    sourceOrderId, sourceDecisionId, expiresAt);
            return true;
        }
        return db.jdbc.update(
                "UPDATE tb_risk_profile SET risk_level=?,decision=?,score=?,reason_codes=?,source_order_id=?,"
                        + "source_decision_id=?,version=version+1,expires_at=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE subject_type=? AND subject_value=? "
                        + "AND (decision<>'BLOCK' OR expires_at<=CURRENT_TIMESTAMP OR ?='BLOCK') "
                        + "AND NOT EXISTS (SELECT 1 FROM tb_ticket_order old_order, tb_ticket_order new_order "
                        + "WHERE old_order.id=tb_risk_profile.source_order_id AND new_order.id=? "
                        + "AND old_order.created_at>new_order.created_at)",
                riskLevel, decision, score, reasonCodes, sourceOrderId, sourceDecisionId, expiresAt,
                subjectType, subjectValue, decision, sourceOrderId) == 1;
    }

    static RiskProfile map(Map<String, Object> row) {
        if (row == null) return null;
        RiskProfile p = new RiskProfile();
        p.setId(str(row.get("id")));
        p.setSubjectType(str(row.get("subject_type")));
        p.setSubjectValue(str(row.get("subject_value")));
        p.setRiskLevel(str(row.get("risk_level")));
        p.setDecision(str(row.get("decision")));
        p.setScore(row.get("score") == null ? 0 : ((Number) row.get("score")).intValue());
        p.setReasonCodes(str(row.get("reason_codes")));
        p.setSourceOrderId(str(row.get("source_order_id")));
        p.setSourceDecisionId(str(row.get("source_decision_id")));
        p.setVersion(row.get("version") == null ? 0 : ((Number) row.get("version")).intValue());
        p.setExpiresAt((Timestamp) row.get("expires_at"));
        p.setCreatedAt((Timestamp) row.get("created_at"));
        p.setUpdatedAt((Timestamp) row.get("updated_at"));
        return p;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}

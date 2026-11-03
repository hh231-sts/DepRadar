package io.depradar.governance;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GovernanceMapper {
    record Policy(String projectId,int version,String blockedSeverities,boolean requireReview,int evidenceHours,int exceptionDays,String createdBy,Instant createdAt) {}
    record Review(String id,String releaseId,String sbomHash,String scanId,String reportHash,int policyVersion,String decision,int version,String decidedBy,String reason,Instant decidedAt) {}
    record ExceptionRequest(String id,String scanId,String findingId,String requestedBy,String reason,Instant expiresAt,String decision,int version,String decidedBy,String decisionReason,Instant decidedAt) {}
    record Audit(String id,String projectId,String actorId,String action,String resourceId,String detailJson,Instant createdAt) {}
    @Select("SELECT * FROM policies WHERE project_id=#{project} AND version=#{version}") Policy policy(String project,int version);
    @Select("SELECT * FROM policies WHERE project_id=#{project} ORDER BY version DESC") List<Policy> policies(String project);
    @Insert("INSERT INTO policies(project_id,version,blocked_severities,require_review,evidence_hours,exception_days,created_by) VALUES(#{project},#{version},#{severities},#{review},#{hours},#{days},#{user})") void addPolicy(String project,int version,String severities,boolean review,int hours,int days,String user);
    @Update("UPDATE projects SET current_policy=#{version} WHERE id=#{project}") void currentPolicy(String project,int version);
    @Insert("INSERT INTO reviews(id,release_id,sbom_hash,scan_id,report_hash,policy_version) VALUES(#{id},#{release},#{hash},#{scan},#{report},#{policy})") void addReview(String id,String release,String hash,String scan,String report,int policy);
    @Select("SELECT * FROM reviews WHERE scan_id=#{scan}") Review review(String scan);
    @Update("UPDATE reviews SET decision=#{decision},version=version+1,decided_by=#{actor},reason=#{reason},decided_at=#{now} WHERE scan_id=#{scan} AND version=#{expected}") int decideReview(String scan,int expected,String decision,String actor,String reason,Instant now);
    @Insert("INSERT INTO exceptions(id,scan_id,finding_id,requested_by,reason,expires_at) VALUES(#{id},#{scan},#{finding},#{user},#{reason},#{expires})") void request(String id,String scan,String finding,String user,String reason,Instant expires);
    @Select("SELECT * FROM exceptions WHERE id=#{id}") ExceptionRequest exception(String id);
    @Select("SELECT * FROM exceptions WHERE scan_id=#{scan} ORDER BY id DESC") List<ExceptionRequest> exceptions(String scan);
    @Update("UPDATE exceptions SET decision=#{decision},version=version+1,decided_by=#{actor},decision_reason=#{reason},decided_at=#{now} WHERE id=#{id} AND version=#{expected} AND decision='PENDING'") int decideException(String id,int expected,String decision,String actor,String reason,Instant now);
    @Insert("INSERT INTO audit_events(id,project_id,actor_id,action,resource_id,detail_json) VALUES(#{id},#{project},#{actor},#{action},#{resource},#{detail})") void audit(String id,String project,String actor,String action,String resource,String detail);
    @Select("SELECT * FROM audit_events WHERE project_id=#{project} AND (#{cursor} IS NULL OR id < #{cursor}) ORDER BY id DESC LIMIT 51") List<Audit> auditPage(String project,String cursor);
}

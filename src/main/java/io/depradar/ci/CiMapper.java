package io.depradar.ci;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CiMapper {
    record Credential(String id,String projectId,String name,Instant revokedAt,Instant createdAt) {}
    @Select("SELECT id,project_id,name,revoked_at,created_at FROM ci_credentials WHERE token_hash=#{hash} AND revoked_at IS NULL") Credential byHash(String hash);
    @Select("SELECT id,project_id,name,revoked_at,created_at FROM ci_credentials WHERE project_id=#{project} ORDER BY id DESC") List<Credential> list(String project);
    @Insert("INSERT INTO ci_credentials(id,project_id,name,token_hash,created_by) VALUES(#{id},#{project},#{name},#{hash},#{actor})") void create(String id,String project,String name,String hash,String actor);
    @Update("UPDATE ci_credentials SET revoked_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND project_id=#{project} AND revoked_at IS NULL") int revoke(String project,String id);
}

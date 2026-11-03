package io.depradar.catalog;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogMapper {
    record Project(String id,String teamId,String name,int currentPolicy,Instant createdAt) {}
    record Release(String id,String projectId,String tag,String sbomHash,String rootRef,Instant createdAt) {}
    record Component(String id,String releaseId,String bomRef,String groupName,String name,String version,String purl) {}
    record Edge(String fromRef,String toRef) {}
    record Raw(byte[] rawSbom) {}
    record Idempotency(String requestHash,String resourceId) {}
    @Select("SELECT * FROM projects WHERE id=#{id}") Project project(String id);
    @Select("SELECT * FROM projects WHERE id=#{id} FOR UPDATE") Project lockProject(String id);
    @Select("SELECT * FROM projects WHERE team_id=#{team} AND (#{cursor} IS NULL OR id < #{cursor}) ORDER BY id DESC LIMIT 51") List<Project> projects(String team,String cursor);
    @Insert("INSERT INTO projects(id,team_id,name) VALUES(#{id},#{team},#{name})") void addProject(String id,String team,String name);
    @Insert("INSERT INTO policies(project_id,version,blocked_severities,require_review,evidence_hours,exception_days,created_by) VALUES(#{project},1,'CRITICAL,HIGH,UNKNOWN',TRUE,24,7,#{user})") void defaultPolicy(String project,String user);
    @Select("SELECT id,project_id,tag,sbom_hash,root_ref,created_at FROM releases WHERE id=#{id}") Release release(String id);
    @Select("SELECT id,project_id,tag,sbom_hash,root_ref,created_at FROM releases WHERE id=#{id} FOR UPDATE") Release lockRelease(String id);
    @Select("SELECT id,project_id,tag,sbom_hash,root_ref,created_at FROM releases WHERE project_id=#{project} AND tag=#{tag}") Release byTag(String project,String tag);
    @Select("SELECT id,project_id,tag,sbom_hash,root_ref,created_at FROM releases WHERE project_id=#{project} AND sbom_hash=#{hash}") Release byHash(String project,String hash);
    @Select("SELECT id,project_id,tag,sbom_hash,root_ref,created_at FROM releases WHERE project_id=#{project} AND (#{cursor} IS NULL OR id < #{cursor}) ORDER BY id DESC LIMIT 51") List<Release> releases(String project,String cursor);
    @Insert("INSERT INTO releases(id,project_id,tag,sbom_hash,raw_sbom,root_ref) VALUES(#{id},#{project},#{tag},#{hash},#{raw},#{root})") void addRelease(String id,String project,String tag,String hash,byte[] raw,String root);
    @Select("SELECT raw_sbom FROM releases WHERE id=#{id}") Raw raw(String id);
    @Insert("INSERT INTO components(id,release_id,bom_ref,group_name,name,version,purl) VALUES(#{id},#{releaseId},#{bomRef},#{groupName},#{name},#{version},#{purl})") void addComponent(Component c);
    @Select("SELECT * FROM components WHERE release_id=#{release} ORDER BY id") List<Component> components(String release);
    @Select("SELECT * FROM components WHERE release_id=#{release} AND (#{cursor} IS NULL OR id < #{cursor}) ORDER BY id DESC LIMIT 51") List<Component> componentPage(String release,String cursor);
    @Insert("INSERT INTO dependency_edges(release_id,from_ref,to_ref) VALUES(#{release},#{from},#{to})") void addEdge(String release,String from,String to);
    @Select("SELECT from_ref,to_ref FROM dependency_edges WHERE release_id=#{release} ORDER BY from_ref,to_ref") List<Edge> edges(String release);
    @Select("SELECT request_hash,resource_id FROM idempotency_keys WHERE project_id=#{project} AND operation=#{operation} AND request_key=#{key}") Idempotency idempotency(String project,String operation,String key);
    @Insert("INSERT INTO idempotency_keys(project_id,operation,request_key,request_hash,resource_id) VALUES(#{project},#{operation},#{key},#{hash},#{resource})") void remember(String project,String operation,String key,String hash,String resource);
}

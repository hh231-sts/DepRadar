package io.depradar.scan;

import io.depradar.catalog.CatalogMapper.Component;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScanMapper {
    record Scan(String id,String releaseId,int ordinal,int policyVersion,String status,String reportHash,Instant evidenceAt,Instant startedAt,Instant completedAt,Instant createdAt) {}
    record Batch(String id,String scanId,int ordinal,String status,int attempts,String leaseToken,Instant leaseUntil,Instant availableAt,String error) {}
    record Result(String scanId,String componentId,String source,String status,Instant queriedAt,String evidenceJson,String error) {}
    record Finding(String id,String scanId,String componentId,String vulnerabilityId,String severity,String summary,String evidenceJson,String pathJson,String sourceUrl,Instant queriedAt) {}
    record Event(String id,String batchId,Instant availableAt,Instant sentAt,int attempts,String lastError) {}
    @Select("SELECT * FROM scans WHERE id=#{id}") Scan scan(String id);
    @Select("SELECT * FROM scans WHERE id=#{id} FOR UPDATE") Scan lockScan(String id);
    @Select("SELECT * FROM scans WHERE release_id=#{release} ORDER BY ordinal DESC LIMIT 1") Scan latest(String release);
    @Select("SELECT * FROM scans WHERE release_id=#{release} AND (#{cursor} IS NULL OR ordinal < (SELECT ordinal FROM scans WHERE id=#{cursor} AND release_id=#{release})) ORDER BY ordinal DESC LIMIT 51") List<Scan> scans(String release,String cursor);
    @Select("SELECT COUNT(*) FROM scans s JOIN releases r ON r.id=s.release_id WHERE r.project_id=#{project} AND s.status IN ('QUEUED','RUNNING')") int active(String project);
    @Select("SELECT COUNT(*) FROM scans WHERE status IN ('QUEUED','RUNNING')") int activeTotal();
    @Select("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL") int pendingEvents();
    @Insert("INSERT INTO scans(id,release_id,ordinal,policy_version,status) VALUES(#{id},#{release},#{ordinal},#{policy},'QUEUED')") void addScan(String id,String release,int ordinal,int policy);
    @Insert("INSERT INTO scan_batches(id,scan_id,ordinal) VALUES(#{id},#{scan},#{ordinal})") void addBatch(String id,String scan,int ordinal);
    @Insert("INSERT INTO batch_components(batch_id,component_id) VALUES(#{batch},#{component})") void addBatchComponent(String batch,String component);
    @Select("SELECT * FROM scan_batches WHERE id=#{id}") Batch batch(String id);
    @Select("SELECT * FROM scan_batches WHERE id=#{id} FOR UPDATE") Batch lockBatch(String id);
    @Select("SELECT * FROM scan_batches WHERE scan_id=#{scan} ORDER BY ordinal") List<Batch> batches(String scan);
    @Select("SELECT c.* FROM components c JOIN batch_components b ON b.component_id=c.id WHERE b.batch_id=#{batch} ORDER BY c.id") List<Component> batchComponents(String batch);
    @Update("UPDATE scan_batches SET status='RUNNING',attempts=attempts+1,lease_token=#{token},lease_until=#{until},error=NULL WHERE id=#{id}") void claim(String id,String token,Instant until);
    @Update("UPDATE scans SET status='RUNNING',started_at=COALESCE(started_at,#{now}) WHERE id=#{id} AND status='QUEUED'") void running(String id,Instant now);
    @Update("UPDATE scan_batches SET lease_until=#{until} WHERE id=#{id} AND lease_token=#{token} AND status='RUNNING' AND lease_until>#{now}") int renew(String id,String token,Instant now,Instant until);
    @Update("UPDATE scan_batches SET status='QUEUED',lease_token=NULL,lease_until=NULL,available_at=#{at},error=#{error} WHERE id=#{id}") void retry(String id,Instant at,String error);
    @Update("UPDATE scan_batches SET status=#{status},lease_token=NULL,lease_until=NULL,error=#{error} WHERE id=#{id}") void finishBatch(String id,String status,String error);
    @Insert("INSERT INTO component_results(scan_id,component_id,source,status,queried_at,evidence_json,error) VALUES(#{scanId},#{componentId},#{source},#{status},#{queriedAt},#{evidenceJson},#{error})") void addResult(Result result);
    @Insert("INSERT INTO findings(id,scan_id,component_id,vulnerability_id,severity,summary,evidence_json,path_json,source_url,queried_at) VALUES(#{id},#{scanId},#{componentId},#{vulnerabilityId},#{severity},#{summary},#{evidenceJson},#{pathJson},#{sourceUrl},#{queriedAt})") void addFinding(Finding finding);
    @Select("SELECT * FROM component_results WHERE scan_id=#{scan} ORDER BY component_id") List<Result> results(String scan);
    @Select("SELECT * FROM findings WHERE scan_id=#{scan} ORDER BY component_id,vulnerability_id") List<Finding> findings(String scan);
    @Select("SELECT * FROM findings WHERE scan_id=#{scan} AND (#{cursor} IS NULL OR id < #{cursor}) ORDER BY id DESC LIMIT 51") List<Finding> findingPage(String scan,String cursor);
    @Select("SELECT * FROM findings WHERE id=#{id}") Finding finding(String id);
    @Update("UPDATE scans SET status=#{status},report_hash=#{hash},evidence_at=#{evidence},completed_at=#{now} WHERE id=#{id}") void finishScan(String id,String status,String hash,Instant evidence,Instant now);
    @Update("UPDATE scans SET status='CANCELLED',completed_at=#{now} WHERE id=#{id}") void cancel(String id,Instant now);
    @Update("UPDATE scan_batches SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE scan_id=#{scan} AND status IN ('QUEUED','RUNNING')") void cancelBatches(String scan);
    @Insert("INSERT INTO outbox(id,batch_id,available_at) VALUES(#{id},#{batch},#{at})") void event(String id,String batch,Instant at);
    @Select("SELECT * FROM outbox WHERE sent_at IS NULL AND available_at<=#{now} ORDER BY available_at LIMIT 50") List<Event> events(Instant now);
    @Update("UPDATE outbox SET sent_at=#{now} WHERE id=#{id} AND sent_at IS NULL") void sent(String id,Instant now);
    @Update("UPDATE outbox SET attempts=attempts+1,available_at=#{at},last_error=#{error} WHERE id=#{id} AND sent_at IS NULL") void sendFailed(String id,Instant at,String error);
    @Select("SELECT b.id FROM scan_batches b JOIN scans s ON s.id=b.scan_id WHERE s.status IN ('QUEUED','RUNNING') AND ((b.status='RUNNING' AND b.lease_until<=#{now}) OR (b.status='QUEUED' AND b.available_at<=#{now} AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.batch_id=b.id AND (o.sent_at IS NULL OR o.sent_at>#{recent})))) LIMIT 100") List<String> recoverable(Instant now,Instant recent);
}

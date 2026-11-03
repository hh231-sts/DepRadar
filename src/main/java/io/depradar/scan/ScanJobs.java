package io.depradar.scan;

import io.depradar.catalog.CatalogService;
import io.depradar.catalog.CatalogMapper;
import io.depradar.governance.GovernanceMapper;
import io.depradar.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanJobs {
    public record Lease(ScanMapper.Batch batch,String token,List<CatalogMapper.Component> components) {}
    public static class LostLease extends RuntimeException {}
    private final ScanMapper db;private final CatalogMapper catalog;private final CatalogService catalogs;private final GovernanceMapper governance;private final Json json;private final Clock clock;private final MeterRegistry metrics;
    @Value("${depradar.lease-seconds}") private int leaseSeconds;
    @Value("${depradar.max-attempts}") private int maxAttempts;
    public ScanJobs(ScanMapper db,CatalogMapper catalog,CatalogService catalogs,GovernanceMapper governance,Json json,Clock clock,MeterRegistry metrics) {
        this.db=db;this.catalog=catalog;this.catalogs=catalogs;this.governance=governance;this.json=json;this.clock=clock;this.metrics=metrics;
    }
    private boolean active(ScanMapper.Scan s) { return Set.of("QUEUED","RUNNING").contains(s.status()); }
    @Transactional public Lease claim(String id) {
        var candidate=db.batch(id);if(candidate==null)throw new IllegalArgumentException("消息中的批次不存在: "+id);
        var scan=db.lockScan(candidate.scanId());var batch=db.lockBatch(id);Instant now=clock.instant();
        if(!active(scan) || !batch.status().equals("QUEUED") || batch.availableAt().isAfter(now))return null;
        var components=db.batchComponents(id);
        if(batch.attempts()>=maxAttempts) {
            persist(scan,batch,failures(components,"任务租约耗尽，来源资料不完整"));return null;
        }
        String token=Values.id();db.claim(id,token,now.plusSeconds(leaseSeconds));db.running(scan.id(),now);
        return new Lease(db.batch(id),token,components);
    }
    public void renew(Lease lease) {
        Instant now=clock.instant();if(db.renew(lease.batch().id(),lease.token(),now,now.plusSeconds(leaseSeconds))!=1)throw new LostLease();
    }
    public List<VulnerabilitySource.Answer> failures(List<CatalogMapper.Component> components,String error) {
        return components.stream().map(c->new VulnerabilitySource.Answer(c,clock.instant(),"UNAVAILABLE",false,List.of(),json.read("{}"),error)).toList();
    }
    @Transactional public void complete(Lease lease,List<VulnerabilitySource.Answer> answers) {
        var scan=db.lockScan(lease.batch().scanId());var batch=db.lockBatch(lease.batch().id());
        if(!active(scan) || !lease.token().equals(batch.leaseToken()) || batch.leaseUntil().isBefore(clock.instant()))return;
        boolean failed=answers.stream().anyMatch(a->!a.complete());
        if(failed && batch.attempts()<maxAttempts) {
            String error=answers.stream().filter(a->!a.complete()).map(VulnerabilitySource.Answer::error).findFirst().orElseThrow();
            Instant at=clock.instant().plusSeconds(5L*batch.attempts());db.retry(batch.id(),at,error);db.event(Values.id(),batch.id(),at);
            metrics.counter("depradar.batch.retries").increment();return;
        }
        persist(scan,batch,answers);
    }
    private void persist(ScanMapper.Scan scan,ScanMapper.Batch batch,List<VulnerabilitySource.Answer> answers) {
        Map<String,List<String>> paths=catalogs.paths(scan.releaseId());
        for(var answer:answers) {
            db.addResult(new ScanMapper.Result(scan.id(),answer.component().id(),answer.source(),answer.complete()?"COMPLETE":"INCOMPLETE",answer.queriedAt(),json.write(answer.evidence()),answer.error()));
            for(var a:answer.advisories())db.addFinding(new ScanMapper.Finding(Values.id(),scan.id(),answer.component().id(),a.id(),a.severity(),a.summary(),json.write(a.evidence()),json.write(paths.getOrDefault(answer.component().bomRef(),List.of())),a.url(),answer.queriedAt()));
        }
        String error=answers.stream().filter(a->!a.complete()).map(VulnerabilitySource.Answer::error).findFirst().orElse(null);
        db.finishBatch(batch.id(),error==null?"SUCCEEDED":"FAILED",error);
        if(db.batches(scan.id()).stream().anyMatch(b->Set.of("QUEUED","RUNNING").contains(b.status())))return;
        var results=db.results(scan.id());long complete=results.stream().filter(r->r.status().equals("COMPLETE")).count();
        String status=complete==results.size()?"SUCCEEDED":complete==0?"FAILED":"PARTIAL";
        Instant evidence=results.stream().map(ScanMapper.Result::queriedAt).min(Comparator.naturalOrder()).orElseThrow();
        String hash=Values.hash(json.write(Map.of("scanId",scan.id(),"results",results,"findings",db.findings(scan.id()))));
        db.finishScan(scan.id(),status,hash,evidence,clock.instant());
        var release=catalog.release(scan.releaseId());
        governance.addReview(Values.id(),release.id(),release.sbomHash(),scan.id(),hash,scan.policyVersion());
        metrics.counter("depradar.scans.completed","status",status).increment();
    }
    @Transactional public void recover(String id) {
        var candidate=db.batch(id);var scan=db.lockScan(candidate.scanId());var batch=db.lockBatch(id);Instant now=clock.instant();
        if(!active(scan))return;
        if(batch.status().equals("RUNNING") && !batch.leaseUntil().isAfter(now)) {
            db.retry(id,now,"worker 租约过期，重新投递");db.event(Values.id(),id,now);metrics.counter("depradar.batch.recoveries").increment();
        } else if(batch.status().equals("QUEUED") && !batch.availableAt().isAfter(now))db.event(Values.id(),id,now);
    }
}

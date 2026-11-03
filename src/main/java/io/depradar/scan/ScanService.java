package io.depradar.scan;

import io.depradar.catalog.*;
import io.depradar.identity.Access;
import io.depradar.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanService {
    private final ScanMapper db; private final CatalogMapper catalog;private final CatalogService catalogs;private final Access access;private final StringRedisTemplate redis;private final Clock clock;
    @Value("${depradar.batch-size}") private int batchSize;
    @Value("${depradar.max-active-scans}") private int maxActive;
    @Value("${depradar.scan-requests-per-minute}") private int rate;
    public ScanService(ScanMapper db,CatalogMapper catalog,CatalogService catalogs,Access access,StringRedisTemplate redis,Clock clock) { this.db=db;this.catalog=catalog;this.catalogs=catalogs;this.access=access;this.redis=redis;this.clock=clock; }
    @Transactional public ScanMapper.Scan submit(String release,String key) {
        var r=access.release(release,Access.Right.WRITE);var p=catalog.lockProject(r.projectId());
        String hash=Values.hash(release),old=catalogs.replay(p.id(),"SCAN",key,hash);
        if(old!=null) return db.scan(old);
        long minute=clock.instant().getEpochSecond()/60;
        Long count=redis.execute(new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],120) end; return n",Long.class),List.of("depradar:scan-rate:"+p.id()+":"+minute));
        if(count>rate) throw new Problem(HttpStatus.TOO_MANY_REQUESTS,"项目每分钟最多提交 "+rate+" 次扫描");
        if(db.active(p.id())>=maxActive) throw new Problem(HttpStatus.TOO_MANY_REQUESTS,"项目并发扫描配额已满");
        catalog.lockRelease(release);var latest=db.latest(release);String id=Values.id();
        db.addScan(id,release,latest==null?1:latest.ordinal()+1,p.currentPolicy());
        List<CatalogMapper.Component> components=catalog.components(release);
        for(int i=0;i<components.size();i+=batchSize) {
            String batch=Values.id();db.addBatch(batch,id,i/batchSize);
            components.subList(i,Math.min(i+batchSize,components.size())).forEach(c->db.addBatchComponent(batch,c.id()));
            db.event(Values.id(),batch,clock.instant());
        }
        catalogs.remember(p.id(),"SCAN",key,hash,id);return db.scan(id);
    }
    public ScanMapper.Scan authorized(String id,Access.Right right) {
        var scan=db.scan(id);if(scan==null) throw Problem.missing("扫描不存在");access.release(scan.releaseId(),right);return scan;
    }
    public Object progress(String id) { var scan=authorized(id,Access.Right.READ);return Map.of("scan",scan,"batches",db.batches(id)); }
    public Values.Page<ScanMapper.Scan> list(String release,String cursor) { access.release(release,Access.Right.READ);return Values.page(db.scans(release,cursor),ScanMapper.Scan::id); }
    @Transactional public void cancel(String id) {
        authorized(id,Access.Right.WRITE);var scan=db.lockScan(id);
        if(!Set.of("QUEUED","RUNNING").contains(scan.status())) throw Problem.conflict("只能取消排队或运行中的扫描");
        db.cancel(id,clock.instant());db.cancelBatches(id);
    }
}

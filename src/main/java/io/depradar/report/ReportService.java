package io.depradar.report;

import io.depradar.catalog.CatalogMapper;
import io.depradar.identity.Access;
import io.depradar.scan.*;
import io.depradar.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final ScanMapper db;private final ScanService scans;private final CatalogMapper catalog;private final Access access;private final Json json;
    public ReportService(ScanMapper db,ScanService scans,CatalogMapper catalog,Access access,Json json) {this.db=db;this.scans=scans;this.catalog=catalog;this.access=access;this.json=json;}
    public Object report(String scan) {var s=scans.authorized(scan,Access.Right.READ);return Map.of("scan",s,"results",db.results(scan),"findingCount",db.findings(scan).size());}
    public Values.Page<ScanMapper.Finding> findings(String scan,String cursor) {scans.authorized(scan,Access.Right.READ);return Values.page(db.findingPage(scan,cursor),ScanMapper.Finding::id);}
    public Object finding(String id) {var f=db.finding(id);if(f==null)throw Problem.missing("风险不存在");scans.authorized(f.scanId(),Access.Right.READ);return f;}
    public Object path(String id) {finding(id);var f=db.finding(id);return Map.of("path",json.read(f.pathJson()),"meaning","清单声明的一条最短引入路径；空数组表示清单未声明从根到此组件的路径");}
    public record Comparison(Object from,Object to,boolean riskComparisonComplete,List<?> addedComponents,List<?> removedComponents,List<?> addedRisks,List<?> resolvedRisks,List<?> unresolvedRisks,String explanation) {}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Comparison compare(String project,String from,String to) {
        access.project(project,Access.Right.READ);var left=scans.authorized(from,Access.Right.READ);var right=scans.authorized(to,Access.Right.READ);
        var lr=catalog.release(left.releaseId());var rr=catalog.release(right.releaseId());
        if(!lr.projectId().equals(project) || !rr.projectId().equals(project))throw Problem.bad("对比扫描必须属于当前项目");
        var lc=catalog.components(lr.id());var rc=catalog.components(rr.id());
        Map<String,CatalogMapper.Component> l=new TreeMap<>(),r=new TreeMap<>();lc.forEach(c->l.put(componentKey(c),c));rc.forEach(c->r.put(componentKey(c),c));
        List<?> added=r.entrySet().stream().filter(e->!l.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<?> removed=l.entrySet().stream().filter(e->!r.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        Map<String,String> lid=new HashMap<>(),rid=new HashMap<>();lc.forEach(c->lid.put(c.id(),componentKey(c)));rc.forEach(c->rid.put(c.id(),componentKey(c)));
        Map<String,ScanMapper.Finding> lf=new TreeMap<>(),rf=new TreeMap<>();
        db.findings(from).forEach(f->lf.put(lid.get(f.componentId())+"|"+f.vulnerabilityId(),f));db.findings(to).forEach(f->rf.put(rid.get(f.componentId())+"|"+f.vulnerabilityId(),f));
        boolean complete=left.status().equals("SUCCEEDED") && right.status().equals("SUCCEEDED");
        var missing=lf.entrySet().stream().filter(e->!rf.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        return new Comparison(left,right,complete,added,removed,rf.entrySet().stream().filter(e->!lf.containsKey(e.getKey())).map(Map.Entry::getValue).toList(),complete?missing:List.of(),complete?List.of():missing,complete?"两侧查询完整；风险差异按 Maven 坐标、版本和公告 ID 比较":"至少一侧查询不完整；未再次检出的旧风险列为未确定，不能认定为已消失");
    }
    private String componentKey(CatalogMapper.Component c) {return c.groupName()+":"+c.name()+":"+c.version();}
}

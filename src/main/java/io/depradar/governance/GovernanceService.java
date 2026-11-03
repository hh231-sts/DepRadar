package io.depradar.governance;

import io.depradar.catalog.CatalogMapper;
import io.depradar.identity.Access;
import io.depradar.scan.*;
import io.depradar.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class GovernanceService {
    public record Binding(String releaseId,String sbomHash,String scanId,String reportHash,int policyVersion) {}
    public record Gate(String result,List<String> reasons,Binding binding,Instant evaluatedAt,Instant validUntil) {}
    private final GovernanceMapper db;private final CatalogMapper catalog;private final ScanMapper scans;private final ScanService scanService;private final Access access;private final Json json;private final Clock clock;
    public GovernanceService(GovernanceMapper db,CatalogMapper catalog,ScanMapper scans,ScanService scanService,Access access,Json json,Clock clock) {this.db=db;this.catalog=catalog;this.scans=scans;this.scanService=scanService;this.access=access;this.json=json;this.clock=clock;}
    public List<GovernanceMapper.Policy> policies(String project) {access.project(project,Access.Right.READ);return db.policies(project);}
    @Transactional public GovernanceMapper.Policy policy(String project,int expected,Set<String> severities,boolean review,int hours,int days) {
        access.project(project,Access.Right.ADMIN);var p=catalog.lockProject(project);
        Values.require(!severities.isEmpty() && Set.of("CRITICAL","HIGH","MODERATE","LOW","UNKNOWN").containsAll(severities),"阻断等级无效");
        Values.require(hours>=1 && days>=1 && days<=7,"证据有效期至少为 1 小时，例外期限为 1 至 7 天");
        if(p.currentPolicy()!=expected)throw Problem.conflict("规则版本已变更");
        int version=expected+1;db.addPolicy(project,version,String.join(",",new TreeSet<>(severities)),review,hours,days,access.human());db.currentPolicy(project,version);
        audit(project,"POLICY_CHANGED",project,Map.of("from",expected,"to",version));return db.policy(project,version);
    }
    public GovernanceMapper.Review review(String scan) {scanService.authorized(scan,Access.Right.READ);return db.review(scan);}
    public List<GovernanceMapper.ExceptionRequest> exceptions(String scan) {scanService.authorized(scan,Access.Right.READ);return db.exceptions(scan);}
    public Values.Page<GovernanceMapper.Audit> audit(String project,String cursor) {access.project(project,Access.Right.READ);return Values.page(db.auditPage(project,cursor),GovernanceMapper.Audit::id);}
    private record Current(ScanMapper.Scan scan,CatalogMapper.Release release,GovernanceMapper.Policy policy) {}
    private Current lockCurrent(String scanId,Access.Right right) {
        var scan=scanService.authorized(scanId,right);var release=catalog.release(scan.releaseId());
        var project=catalog.lockProject(release.projectId());scan=scans.lockScan(scanId);
        if(!scans.latest(release.id()).id().equals(scanId))throw Problem.conflict("已有新扫描，请审查最新报告");
        if(scan.reportHash()==null)throw Problem.conflict("扫描尚未形成报告");
        if(project.currentPolicy()!=scan.policyVersion())throw Problem.conflict("规则已变更，请重新扫描和审查");
        return new Current(scan,release,db.policy(project.id(),project.currentPolicy()));
    }
    @Transactional public GovernanceMapper.Review decide(String scan,int expected,String hash,String sbom,int policy,String decision,String reason) {
        var current=lockCurrent(scan,Access.Right.REVIEW);var report=current.scan();
        Values.require(Set.of("APPROVED","REJECTED").contains(decision),"决定须为 APPROVED 或 REJECTED");
        if(!report.reportHash().equals(hash) || !current.release().sbomHash().equals(sbom) || report.policyVersion()!=policy)throw Problem.conflict("审查绑定与报告不符");
        if(db.decideReview(scan,expected,decision,access.human(),reason,clock.instant())!=1)throw Problem.conflict("审查已被其他人修改，请刷新");
        audit(current.release().projectId(),"REVIEW_"+decision,scan,Map.of("expectedVersion",expected,"binding",binding(current.release(),report),"reason",reason));return db.review(scan);
    }
    @Transactional public GovernanceMapper.ExceptionRequest request(String scan,String finding,String reason,Instant expires) {
        var current=lockCurrent(scan,Access.Right.WRITE);String user=access.human();var f=scans.finding(finding);
        if(f==null || !f.scanId().equals(scan))throw Problem.bad("风险条目不属于该报告");
        Instant now=clock.instant();Values.require(expires.isAfter(now) && !expires.isAfter(now.plus(Duration.ofDays(current.policy().exceptionDays()))),"例外期限须在当前时间和规则最大期限之间");
        String id=Values.id();db.request(id,scan,finding,user,reason,expires);
        audit(current.release().projectId(),"EXCEPTION_REQUESTED",id,Map.of("findingId",finding,"expiresAt",expires,"binding",binding(current.release(),current.scan()),"reason",reason));return db.exception(id);
    }
    @Transactional public GovernanceMapper.ExceptionRequest decideException(String id,int expected,String decision,String reason) {
        var exception=db.exception(id);if(exception==null)throw Problem.missing("例外申请不存在");var current=lockCurrent(exception.scanId(),Access.Right.REVIEW);
        Values.require(Set.of("APPROVED","REJECTED").contains(decision),"决定须为 APPROVED 或 REJECTED");
        if(exception.requestedBy().equals(access.human()))throw Problem.forbidden();
        if(!exception.expiresAt().isAfter(clock.instant()))throw Problem.conflict("例外已到期");
        if(db.decideException(id,expected,decision,access.human(),reason,clock.instant())!=1)throw Problem.conflict("例外申请已被处理，请刷新");
        audit(current.release().projectId(),"EXCEPTION_"+decision,id,Map.of("reason",reason,"expectedVersion",expected,"binding",binding(current.release(),current.scan())));return db.exception(id);
    }
    private Binding binding(CatalogMapper.Release r,ScanMapper.Scan s) {return new Binding(r.id(),r.sbomHash(),s.id(),s.reportHash(),s.policyVersion());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Gate gate(String releaseId,String expectedScan,String expectedHash) {
        var release=access.release(releaseId,Access.Right.GATE);var project=catalog.project(release.projectId());var scan=scans.latest(releaseId);Instant now=clock.instant();
        Values.require(expectedScan!=null && expectedHash!=null,"门禁查询必须提交 scanId 和 sbomHash");
        if(scan==null || !scan.id().equals(expectedScan) || !release.sbomHash().equals(expectedHash))throw Problem.conflict("预期扫描或清单 hash 与当前版本绑定不符");
        var policy=db.policy(project.id(),project.currentPolicy());var bound=binding(release,scan);var review=db.review(scan.id());
        Instant until=scan.evidenceAt()==null?now:scan.evidenceAt().plus(Duration.ofHours(policy.evidenceHours()));
        boolean samePolicy=scan.policyVersion()==project.currentPolicy();List<String> blockers=new ArrayList<>();
        Set<String> blocked=Set.of(policy.blockedSeverities().split(","));var exceptions=db.exceptions(scan.id());
        for(var finding:scans.findings(scan.id())) if(blocked.contains(finding.severity())) {
            var approved=exceptions.stream().filter(e->e.findingId().equals(finding.id()) && e.decision().equals("APPROVED") && e.expiresAt().isAfter(now) && samePolicy).max(Comparator.comparing(GovernanceMapper.ExceptionRequest::expiresAt));
            if(approved.isEmpty())blockers.add(finding.vulnerabilityId()+" / "+finding.severity()+" / "+finding.componentId());
            else if(approved.get().expiresAt().isBefore(until))until=approved.get().expiresAt();
        }
        if(review!=null && samePolicy && review.decision().equals("REJECTED") && until.isAfter(now))blockers.add("人工审查已拒绝: "+review.reason());
        if(!blockers.isEmpty())return new Gate("BLOCK",List.copyOf(blockers),bound,now,until);
        List<String> missing=new ArrayList<>();
        if(!scan.status().equals("SUCCEEDED"))missing.add("扫描资料不完整或未完成: "+scan.status());
        if(!samePolicy)missing.add("规则已变更，需重新扫描和审查");
        if(!until.isAfter(now))missing.add("报告证据已过期");
        if(policy.requireReview() && (review==null || !review.decision().equals("APPROVED")))missing.add("尚未获得该报告的人工批准");
        return new Gate(missing.isEmpty()?"PASS":"INDETERMINATE",missing.isEmpty()?List.of("报告、规则、例外及人工审查均满足发布条件"):List.copyOf(missing),bound,now,until);
    }
    private void audit(String project,String action,String resource,Object detail) {db.audit(Values.id(),project,access.human(),action,resource,json.write(detail));}
}

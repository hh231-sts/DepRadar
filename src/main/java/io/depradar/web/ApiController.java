package io.depradar.web;

import io.depradar.catalog.*;
import io.depradar.ci.CiService;
import io.depradar.governance.GovernanceService;
import io.depradar.identity.*;
import io.depradar.report.ReportService;
import io.depradar.scan.ScanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final IdentityService identity;private final CatalogService catalog;private final ScanService scans;private final ReportService reports;private final GovernanceService governance;private final CiService ci;private final Access access;
    private final DependenciesRateLimiter dependenciesRateLimiter;
    public ApiController(IdentityService identity,CatalogService catalog,ScanService scans,ReportService reports,GovernanceService governance,CiService ci,Access access,DependenciesRateLimiter dependenciesRateLimiter) {
        this.identity=identity;this.catalog=catalog;this.scans=scans;this.reports=reports;this.governance=governance;this.ci=ci;this.access=access;this.dependenciesRateLimiter=dependenciesRateLimiter;
    }
    public record Name(@NotBlank @Size(max=120) String name) {}
    public record Member(@NotBlank @Size(max=190) String name,@NotBlank String role) {}
    public record Decision(@NotNull @Min(0) Integer expectedVersion,@NotBlank String sbomHash,@NotBlank String reportHash,@Min(1) int policyVersion,@NotBlank String decision,@NotBlank @Size(max=4000) String reason) {}
    public record ExceptionInput(@NotBlank String findingId,@NotBlank @Size(max=4000) String reason,@NotNull Instant expiresAt) {}
    public record ExceptionDecision(@NotNull @Min(0) Integer expectedVersion,@NotBlank String decision,@NotBlank @Size(max=4000) String reason) {}
    public record PolicyInput(@Min(1) int expectedVersion,@NotEmpty Set<String> blockedSeverities,@NotNull Boolean requireReview,@Min(1) int evidenceHours,@Min(1) int exceptionDays) {}
    @GetMapping("/context") public Object context(CsrfToken csrf) {access.human();var a=access.actor();return Map.of("operator",Map.of("id",a.userId(),"name",a.name()),"operators",identity.operators(),"csrfToken",csrf.getToken(),"csrfHeader",csrf.getHeaderName());}
    @GetMapping("/teams") public Object teams() {return identity.teams();}
    @PostMapping("/teams") @ResponseStatus(HttpStatus.CREATED) public Object team(@Valid @RequestBody Name in) {return Map.of("id",identity.createTeam(in.name()));}
    @GetMapping("/teams/{team}/members") public Object members(@PathVariable String team) {return identity.members(team);}
    @PutMapping("/teams/{team}/members") public void member(@PathVariable String team,@Valid @RequestBody Member in) {identity.member(team,in.name(),in.role());}
    @GetMapping("/teams/{team}/projects") public Object projects(@PathVariable String team,@RequestParam(required=false) String cursor) {return catalog.projects(team,cursor);}
    @PostMapping("/teams/{team}/projects") @ResponseStatus(HttpStatus.CREATED) public Object project(@PathVariable String team,@Valid @RequestBody Name in) {return catalog.createProject(team,in.name());}
    @GetMapping("/projects/{project}") public Object project(@PathVariable String project) {return catalog.project(project);}
    @GetMapping("/projects/{project}/releases") public Object releases(@PathVariable String project,@RequestParam(required=false) String cursor) {return catalog.releases(project,cursor);}
    @PostMapping(value="/projects/{project}/releases",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Object upload(@PathVariable String project,@RequestParam @NotBlank @Size(max=120) String tag,@RequestPart MultipartFile file,@RequestHeader(value="Idempotency-Key",required=false) String key) throws IOException {return catalog.upload(project,tag,file.getBytes(),key);}
    @GetMapping("/releases/{release}") public Object release(@PathVariable String release) {return catalog.release(release);}
    @GetMapping(value="/releases/{release}/sbom",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<byte[]> raw(@PathVariable String release) {return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"sbom.json\"").body(catalog.raw(release));}
    @GetMapping("/releases/{release}/components") public Object components(@PathVariable String release,@RequestParam(required=false) String cursor) {return catalog.components(release,cursor);}
    @GetMapping("/releases/{release}/dependencies") public Object dependencies(@PathVariable String release,HttpServletRequest request) {var fallback=dependenciesRateLimiter.check(request);return catalog.graph(release,fallback);}
    @GetMapping("/releases/{release}/scans") public Object scans(@PathVariable String release,@RequestParam(required=false) String cursor) {return scans.list(release,cursor);}
    @PostMapping("/releases/{release}/scans") public ResponseEntity<?> scan(@PathVariable String release,@RequestHeader(value="Idempotency-Key",required=false) String key) {var scan=scans.submit(release,key);return ResponseEntity.accepted().location(URI.create("/api/v1/scans/"+scan.id())).body(scan);}
    @GetMapping("/scans/{scan}") public Object progress(@PathVariable String scan) {return scans.progress(scan);}
    @PostMapping("/scans/{scan}/cancel") public void cancel(@PathVariable String scan) {scans.cancel(scan);}
    @GetMapping("/scans/{scan}/report") public Object report(@PathVariable String scan) {return reports.report(scan);}
    @GetMapping("/scans/{scan}/findings") public Object findings(@PathVariable String scan,@RequestParam(required=false) String cursor) {return reports.findings(scan,cursor);}
    @GetMapping("/findings/{finding}") public Object finding(@PathVariable String finding) {return reports.finding(finding);}
    @GetMapping("/findings/{finding}/paths") public Object paths(@PathVariable String finding) {return reports.path(finding);}
    @GetMapping("/scans/{scan}/review") public Object review(@PathVariable String scan) {return governance.review(scan);}
    @PutMapping("/scans/{scan}/review") public Object decide(@PathVariable String scan,@Valid @RequestBody Decision in) {return governance.decide(scan,in.expectedVersion(),in.reportHash(),in.sbomHash(),in.policyVersion(),in.decision(),in.reason());}
    @GetMapping("/scans/{scan}/exceptions") public Object exceptions(@PathVariable String scan) {return governance.exceptions(scan);}
    @PostMapping("/scans/{scan}/exceptions") @ResponseStatus(HttpStatus.CREATED) public Object exception(@PathVariable String scan,@Valid @RequestBody ExceptionInput in) {return governance.request(scan,in.findingId(),in.reason(),in.expiresAt());}
    @PutMapping("/exceptions/{id}") public Object decideException(@PathVariable String id,@Valid @RequestBody ExceptionDecision in) {return governance.decideException(id,in.expectedVersion(),in.decision(),in.reason());}
    @GetMapping("/projects/{project}/policies") public Object policies(@PathVariable String project) {return governance.policies(project);}
    @PostMapping("/projects/{project}/policies") @ResponseStatus(HttpStatus.CREATED) public Object policy(@PathVariable String project,@Valid @RequestBody PolicyInput in) {return governance.policy(project,in.expectedVersion(),in.blockedSeverities(),in.requireReview(),in.evidenceHours(),in.exceptionDays());}
    @GetMapping("/releases/{release}/gate") public Object gate(@PathVariable String release,@RequestParam String scanId,@RequestParam String sbomHash) {return governance.gate(release,scanId,sbomHash);}
    @GetMapping("/projects/{project}/compare") public Object compare(@PathVariable String project,@RequestParam String fromScanId,@RequestParam String toScanId) {return reports.compare(project,fromScanId,toScanId);}
    @GetMapping("/projects/{project}/audit") public Object audit(@PathVariable String project,@RequestParam(required=false) String cursor) {return governance.audit(project,cursor);}
    @GetMapping("/projects/{project}/ci-credentials") public Object credentials(@PathVariable String project) {return ci.list(project);}
    @PostMapping("/projects/{project}/ci-credentials") @ResponseStatus(HttpStatus.CREATED) public Object credential(@PathVariable String project,@Valid @RequestBody Name in) {return ci.create(project,in.name());}
    @DeleteMapping("/projects/{project}/ci-credentials/{id}") public void revoke(@PathVariable String project,@PathVariable String id) {ci.revoke(project,id);}
}

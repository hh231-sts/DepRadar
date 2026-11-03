package io.depradar;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.depradar.catalog.*;
import io.depradar.ci.CiService;
import io.depradar.governance.*;
import io.depradar.identity.*;
import io.depradar.report.ReportService;
import io.depradar.scan.*;
import io.depradar.shared.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"depradar.scheduling=false","depradar.lease-seconds=2"})
@AutoConfigureMockMvc
@Testcontainers
@Import(WorkflowIntegrationTest.TimeConfig.class)
class WorkflowIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
    @DynamicPropertySource static void services(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);
        r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.host",RABBIT::getHost);r.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username",RABBIT::getAdminUsername);r.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    static class MutableClock extends Clock {
        private volatile Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(3);
        void reset(){now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(3);} void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary MutableClock testClock(){return new MutableClock();} }
    @Autowired MutableClock clock;@Autowired MockMvc mvc;@Autowired Json json;
    @Autowired IdentityService identity;@Autowired IdentityMapper identities;@Autowired CatalogService catalog;
    @Autowired ScanService scans;@Autowired ScanMapper scanDb;@Autowired ScanJobs jobs;
    @Autowired GovernanceService governance;@Autowired CiService ci;@Autowired ReportService reports;
    @Autowired RabbitTemplate rabbit;@Autowired Messaging.Dispatcher dispatcher;
    record WorkspaceActor(Actor actor,String team) {}
    @BeforeEach void before(){clock.reset();}
    @AfterEach void after(){SecurityContextHolder.clearContext();}
    WorkspaceActor operator() {
        String id=Values.id(),name="操作人 "+id,team=Values.id();
        identities.addOperator(new IdentityMapper.Operator(id,name));identities.addTeam(team,"Team "+id);identities.addMember(team,id,"OWNER");
        return new WorkspaceActor(new Actor(id,name,null),team);
    }
    void as(Actor actor){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));}
    jakarta.servlet.http.Cookie selected(Actor actor){return new jakarta.servlet.http.Cookie(LocalIdentity.COOKIE,actor.userId());}
    byte[] sample(String name) throws Exception {return Files.readAllBytes(Path.of("examples",name));}
    CatalogMapper.Project project(WorkspaceActor a){as(a.actor());return catalog.createProject(a.team(),"payment-service");}
    void publishAndWait(String scan) {
        dispatcher.publishPending();
        await().atMost(Duration.ofSeconds(30)).until(()->!Set.of("QUEUED","RUNNING").contains(scanDb.scan(scan).status()));
    }
    void approve(ScanMapper.Scan scan,CatalogMapper.Release release) {
        var review=governance.review(scan.id());governance.decide(scan.id(),review.version(),scanDb.scan(scan.id()).reportHash(),release.sbomHash(),scan.policyVersion(),"APPROVED","已核对依赖风险和例外范围");
    }

    @Test void businessLoopIncludingOnlinePaginationAndCiBinding() throws Exception {
        WorkspaceActor owner=operator(),reviewer=operator();var p=project(owner);
        identity.member(owner.team(),reviewer.actor().name(),"REVIEWER");
        mvc.perform(get("/").with(anonymous())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("DepRadar")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("退出登录"))));
        var bootstrap=mvc.perform(get("/api/v1/context").with(anonymous())).andExpect(status().isOk())
            .andExpect(jsonPath("$.operator.id").value(LocalIdentity.DEFAULT_OPERATOR))
            .andExpect(cookie().doesNotExist("JSESSIONID")).andReturn();
        var browserCsrf=bootstrap.getResponse().getCookie("XSRF-TOKEN");
        var context=json.read(bootstrap.getResponse().getContentAsString());
        mvc.perform(get("/api/v1/context").with(anonymous()).cookie(browserCsrf)).andExpect(status().isOk()).andExpect(cookie().doesNotExist("XSRF-TOKEN"));
        mvc.perform(put("/api/v1/teams/"+LocalIdentity.DEFAULT_TEAM+"/members").with(anonymous()).cookie(browserCsrf)
            .header(context.path("csrfHeader").asText(),context.path("csrfToken").asText()).contentType(MediaType.APPLICATION_JSON)
            .content(json.write(Map.of("name","默认审查人","role","REVIEWER")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/teams").cookie(new jakarta.servlet.http.Cookie(LocalIdentity.COOKIE,LocalIdentity.DEFAULT_REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].role").value("REVIEWER"));
        mvc.perform(get("/login")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        var file=new MockMultipartFile("file","sbom.json","application/json",sample("sbom-v1.json"));
        String imported=mvc.perform(multipart("/api/v1/projects/"+p.id()+"/releases").file(file).param("tag","1.0.0").header("Idempotency-Key","import-1").cookie(selected(owner.actor())).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String releaseId=json.read(imported).path("id").asText();as(owner.actor());var release=catalog.release(releaseId);
        assertThat(catalog.raw(releaseId)).isEqualTo(sample("sbom-v1.json"));
        assertThat(catalog.upload(p.id(),"alias",sample("sbom-v1.json"),null).id()).isEqualTo(releaseId);
        assertThatThrownBy(()->catalog.upload(p.id(),"1.0.0",sample("sbom-v2.json"),null)).isInstanceOf(Problem.class).hasMessageContaining("标签");
        String response=mvc.perform(post("/api/v1/releases/"+releaseId+"/scans").header("Idempotency-Key","scan-1").cookie(selected(owner.actor())).with(csrf()))
            .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        String scanId=json.read(response).path("id").asText();publishAndWait(scanId);as(owner.actor());var scan=scanDb.scan(scanId);
        assertThat(scan.status()).isEqualTo("SUCCEEDED");assertThat(scans.submit(releaseId,"scan-1").id()).isEqualTo(scanId);
        assertThat(governance.gate(releaseId,scanId,release.sbomHash()).result()).isEqualTo("BLOCK");
        var finding=scanDb.findings(scanId).getFirst();assertThat(json.read(finding.pathJson())).hasSize(3);
        var request=governance.request(scanId,finding.id(),"补偿措施已部署",clock.instant().plus(Duration.ofHours(2)));
        assertThatThrownBy(()->governance.decideException(request.id(),0,"APPROVED","自批")).isInstanceOf(Problem.class);
        as(reviewer.actor());governance.decideException(request.id(),0,"APPROVED","已验证隔离措施");
        assertThat(governance.gate(releaseId,scanId,release.sbomHash()).result()).isEqualTo("INDETERMINATE");
        approve(scan,release);assertThat(governance.gate(releaseId,scanId,release.sbomHash()).result()).isEqualTo("PASS");
        assertThatThrownBy(()->governance.decide(scanId,0,scan.reportHash(),release.sbomHash(),1,"REJECTED","过期并发版本")).isInstanceOf(Problem.class).hasMessageContaining("刷新");
        governance.decide(scanId,1,scan.reportHash(),release.sbomHash(),1,"REJECTED","新发现的发布问题");
        assertThat(governance.gate(releaseId,scanId,release.sbomHash()).result()).isEqualTo("BLOCK");
        governance.decide(scanId,2,scan.reportHash(),release.sbomHash(),1,"APPROVED","已解决发布问题");
        as(owner.actor());var credential=ci.create(p.id(),"CI integration");String token=credential.get("token");
        mvc.perform(get("/api/v1/releases/"+releaseId+"/gate").param("scanId",scanId).param("sbomHash",release.sbomHash()).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PASS")).andExpect(jsonPath("$.binding.reportHash").value(scan.reportHash()));
        mvc.perform(get("/api/v1/releases/"+releaseId+"/gate").param("scanId",Values.id()).param("sbomHash",release.sbomHash()).header("Authorization","Bearer "+token)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/scans/"+scanId+"/review").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedVersion",1,"sbomHash",release.sbomHash(),"reportHash",scan.reportHash(),"policyVersion",1,"decision","APPROVED","reason","CI 自批")))).andExpect(status().isForbidden());
        as(owner.actor());ci.revoke(p.id(),credential.get("id"));
        mvc.perform(get("/api/v1/projects/"+p.id()).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        as(owner.actor());assertThat(governance.audit(p.id(),null).items()).extracting(GovernanceMapper.Audit::action).contains("REVIEW_APPROVED","EXCEPTION_APPROVED","CI_CREDENTIAL_REVOKED");
        onlineContract(releaseId);
    }

    private void onlineContract(String release) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger pages=new AtomicInteger();
        JsonNode fixture=json.input(Files.readAllBytes(Path.of("src/main/resources/replay/osv.json")));
        server.createContext("/v1/querybatch",exchange->{
            JsonNode input=json.input(exchange.getRequestBody().readAllBytes());int page=pages.incrementAndGet();
            String result=page==1?"{\"results\":[{\"vulns\":[{\"id\":\"GHSA-jfh8-c2jp-5v3q\"}],\"next_page_token\":\"next\"},{}]}":"{\"results\":[{}]}";
            if(page==2 && (input.path("queries").size()!=1 || !input.path("queries").get(0).has("page_token")))result="{}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/v1/vulns/",exchange->{byte[] bytes=json.write(fixture.path("advisories").path("GHSA-jfh8-c2jp-5v3q")).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();
        try {
            var source=new OsvSource("http://127.0.0.1:"+server.getAddress().getPort(),clock,json);
            var components=List.of(new CatalogMapper.Component("1",release,"a","org.apache.logging.log4j","log4j-core","2.14.1","pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"),new CatalogMapper.Component("2",release,"b","org.apache.commons","commons-lang3","3.17.0","pkg:maven/org.apache.commons/commons-lang3@3.17.0"));
            var answers=source.query(components,()->{});assertThat(pages.get()).isEqualTo(2);assertThat(answers).allMatch(VulnerabilitySource.Answer::complete);
            assertThat(answers.getFirst().advisories().getFirst().severity()).isEqualTo("CRITICAL");
        } finally {server.stop(0);}
    }

    @Test void duplicateDeliveryExpiredLeaseRecoveryAndCancellation() throws Exception {
        WorkspaceActor owner=operator();var p=project(owner);var release=catalog.upload(p.id(),"2.0.0",sample("sbom-v2.json"),null);var scan=scans.submit(release.id(),null);
        var batch=scanDb.batches(scan.id()).getFirst();var abandoned=jobs.claim(batch.id());assertThat(abandoned).isNotNull();
        assertThat(jobs.claim(batch.id())).isNull();clock.advance(Duration.ofSeconds(3));jobs.recover(batch.id());
        var outdated=jobs.failures(abandoned.components(),"旧 worker 的迟到结果");jobs.complete(abandoned,outdated);
        publishAndWait(scan.id());rabbit.convertAndSend(Messaging.QUEUE,batch.id());rabbit.convertAndSend(Messaging.QUEUE,batch.id());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(scanDb.results(scan.id())).hasSize(2));
        assertThat(scanDb.scan(scan.id()).status()).isEqualTo("SUCCEEDED");assertThat(scanDb.batches(scan.id()).getFirst().attempts()).isEqualTo(2);
        assertThat(governance.review(scan.id())).isNotNull();assertThat(jobs.claim(batch.id())).isNull();
        as(owner.actor());var cancelled=scans.submit(release.id(),null);var cBatch=scanDb.batches(cancelled.id()).getFirst();var lease=jobs.claim(cBatch.id());scans.cancel(cancelled.id());
        jobs.complete(lease,jobs.failures(lease.components(),"已取消任务的迟到结果"));rabbit.convertAndSend(Messaging.QUEUE,cBatch.id());
        assertThat(scanDb.scan(cancelled.id()).status()).isEqualTo("CANCELLED");assertThat(scanDb.results(cancelled.id())).isEmpty();
    }

    @Test void gateExpiryRescanPolicyChangeIncompleteComparisonAndTenantIsolation() throws Exception {
        WorkspaceActor owner=operator(),reviewer=operator(),outsider=operator();var p=project(owner);identity.member(owner.team(),reviewer.actor().name(),"REVIEWER");
        var vulnerable=catalog.upload(p.id(),"1",sample("sbom-v1.json"),null);var first=scans.submit(vulnerable.id(),null);publishAndWait(first.id());
        var f=scanDb.findings(first.id()).getFirst();var e=governance.request(first.id(),f.id(),"临时隔离",clock.instant().plus(Duration.ofMinutes(5)));
        as(reviewer.actor());governance.decideException(e.id(),0,"APPROVED","验证通过");approve(first,vulnerable);
        assertThat(governance.gate(vulnerable.id(),first.id(),vulnerable.sbomHash()).result()).isEqualTo("PASS");
        clock.advance(Duration.ofMinutes(6));assertThat(governance.gate(vulnerable.id(),first.id(),vulnerable.sbomHash()).result()).isEqualTo("BLOCK");
        as(owner.actor());var clean=catalog.upload(p.id(),"2",sample("sbom-v2.json"),null);var second=scans.submit(clean.id(),null);publishAndWait(second.id());approve(second,clean);
        assertThat(governance.gate(clean.id(),second.id(),clean.sbomHash()).result()).isEqualTo("PASS");
        clock.advance(Duration.ofHours(25));assertThat(governance.gate(clean.id(),second.id(),clean.sbomHash()).result()).isEqualTo("INDETERMINATE");
        var rescan=scans.submit(clean.id(),null);
        var pendingComparison=reports.compare(p.id(),first.id(),rescan.id());
        assertThat(pendingComparison.unresolvedRisks()).hasSize(1);assertThat(pendingComparison.resolvedRisks()).isEmpty();
        assertThatThrownBy(()->governance.gate(clean.id(),second.id(),clean.sbomHash())).isInstanceOf(Problem.class).hasMessageContaining("绑定");
        publishAndWait(rescan.id());assertThat(governance.gate(clean.id(),rescan.id(),clean.sbomHash()).result()).isEqualTo("INDETERMINATE");approve(rescan,clean);
        governance.policy(p.id(),1,Set.of("CRITICAL","HIGH","UNKNOWN"),true,24,7);
        assertThat(governance.gate(clean.id(),rescan.id(),clean.sbomHash()).result()).isEqualTo("INDETERMINATE");
        assertThatThrownBy(()->approve(rescan,clean)).isInstanceOf(Problem.class).hasMessageContaining("规则");
        var partial=catalog.upload(p.id(),"3",sample("sbom-incomplete.json"),null);var failed=scans.submit(partial.id(),null);
        for(int attempt=0;attempt<3;attempt++) {
            dispatcher.publishPending();final int expected=attempt+1;
            await().atMost(Duration.ofSeconds(20)).until(()->scanDb.batches(failed.id()).getFirst().attempts()>=expected && !scanDb.batches(failed.id()).getFirst().status().equals("RUNNING"));
            clock.advance(Duration.ofSeconds(16));
        }
        assertThat(scanDb.scan(failed.id()).status()).isEqualTo("PARTIAL");
        assertThat(governance.gate(partial.id(),failed.id(),partial.sbomHash()).result()).isEqualTo("BLOCK");
        var exception=governance.request(failed.id(),scanDb.findings(failed.id()).getFirst().id(),"仅豁免已知风险",clock.instant().plus(Duration.ofHours(1)));
        as(reviewer.actor());governance.decideException(exception.id(),0,"APPROVED","资料不足仍不能放行");approve(failed,partial);
        assertThat(governance.gate(partial.id(),failed.id(),partial.sbomHash()).result()).isEqualTo("INDETERMINATE");
        var comparison=reports.compare(p.id(),first.id(),failed.id());assertThat(comparison.riskComparisonComplete()).isFalse();assertThat(comparison.resolvedRisks()).isEmpty();
        mvc.perform(get("/api/v1/releases/"+clean.id()).cookie(selected(outsider.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/scans/"+failed.id()+"/report").cookie(selected(outsider.actor()))).andExpect(status().isForbidden());
    }
}

package io.depradar.scan;

import com.fasterxml.jackson.databind.JsonNode;
import io.depradar.catalog.CatalogMapper.Component;
import io.depradar.shared.Json;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@org.springframework.stereotype.Component
@ConditionalOnProperty(name="depradar.osv-mode",havingValue="online")
public class OsvSource implements VulnerabilitySource {
    private final RestClient http;private final Clock clock;private final Json json;
    public OsvSource(@Value("${depradar.osv-url}") String url,Clock clock,Json json) {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(10));factory.setReadTimeout(Duration.ofSeconds(20));
        this.http=RestClient.builder().baseUrl(url).requestFactory(factory).build();this.clock=clock;this.json=json;
    }
    private static final class Query {
        final Component component;final Instant at;final List<JsonNode> pages=new ArrayList<>();final Set<String> ids=new TreeSet<>();
        final List<Advisory> advisories=new ArrayList<>();String token="",error;
        Query(Component component,Instant at) { this.component=component;this.at=at; }
    }
    @Override public List<Answer> query(List<Component> components,Runnable heartbeat) {
        List<Query> all=components.stream().map(c->new Query(c,clock.instant())).toList();List<Query> active=new ArrayList<>(all);
        while(!active.isEmpty()) {
            List<Map<String,Object>> requests=active.stream().map(q->{
                Map<String,Object> payload=new LinkedHashMap<>();payload.put("package",Map.of("ecosystem","Maven","name",q.component.groupName()+":"+q.component.name()));payload.put("version",q.component.version());
                if(!q.token.isEmpty())payload.put("page_token",q.token);return payload;
            }).toList();
            List<Query> next=new ArrayList<>();
            try {
                JsonNode response=http.post().uri("/v1/querybatch").body(Map.of("queries",requests)).retrieve().body(JsonNode.class);
                if(response==null || !response.path("results").isArray() || response.path("results").size()!=active.size()) throw new IllegalStateException("OSV 批量响应数量不符");
                for(int i=0;i<active.size();i++) {
                    Query q=active.get(i);JsonNode page=response.path("results").get(i);q.pages.add(page);
                    if(!page.isObject() || (page.has("vulns") && !page.path("vulns").isArray()))throw new IllegalStateException("OSV 分页响应格式不符");
                    for(JsonNode item:page.path("vulns")) {
                        String id=item.path("id").asText();if(id.isBlank())throw new IllegalStateException("OSV 返回空公告 ID");q.ids.add(id);
                    }
                    String token=page.path("next_page_token").asText();
                    if(!token.isEmpty() && token.equals(q.token))throw new IllegalStateException("OSV 分页 token 未推进");
                    q.token=token;if(!token.isEmpty())next.add(q);
                }
            } catch(RuntimeException e) { for(Query q:active)q.error="OSV 批量查询失败: "+e.getMessage();next.clear(); }
            heartbeat.run();active=next;
        }
        for(Query q:all) for(String id:q.ids) {
            try {
                JsonNode detail=http.get().uri("/v1/vulns/{id}",id).retrieve().body(JsonNode.class);
                if(detail==null || !id.equals(detail.path("id").asText()))throw new IllegalStateException("OSV 公告 ID 不匹配");
                if(!detail.hasNonNull("withdrawn"))q.advisories.add(new Advisory(id,Severity.from(detail),detail.path("summary").asText(id),"https://osv.dev/vulnerability/"+id,detail));
                // Withdrawn advisories remain part of the immutable query evidence.
                q.pages.add(json.read(json.write(Map.of("detail",detail))));
            } catch(RuntimeException e) { q.error="OSV 公告详情失败 ("+id+"): "+e.getMessage(); }
            heartbeat.run();
        }
        return all.stream().map(q->new Answer(q.component,q.at,"OSV",q.error==null,List.copyOf(q.advisories),json.read(json.write(Map.of("pages",q.pages))),q.error)).toList();
    }
}

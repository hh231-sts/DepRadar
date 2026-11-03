package io.depradar.scan;

import com.fasterxml.jackson.databind.JsonNode;
import io.depradar.catalog.CatalogMapper.Component;
import io.depradar.shared.Json;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;

@org.springframework.stereotype.Component
@ConditionalOnProperty(name="depradar.osv-mode",havingValue="replay")
public class ReplaySource implements VulnerabilitySource {
    private final JsonNode data;private final Clock clock;
    public ReplaySource(Json json,Clock clock) throws IOException {
        this.data=json.input(new ClassPathResource("replay/osv.json").getContentAsByteArray());this.clock=clock;
    }
    @Override public List<Answer> query(List<Component> components,Runnable heartbeat) {
        List<Answer> answers=new ArrayList<>();
        for(Component c:components) {
            String coordinate=c.groupName()+":"+c.name()+":"+c.version();JsonNode replay=data.path("packages").path(coordinate);
            List<Advisory> advisories=new ArrayList<>();String error=null;
            if(replay.isMissingNode()) error="回放未收录坐标: "+coordinate;
            else {
                for(JsonNode page:replay.path("pages"))for(JsonNode item:page.path("vulns")) {
                    String id=item.path("id").asText();JsonNode detail=data.path("advisories").path(id);
                    if(detail.isMissingNode())error="回放缺少公告详情: "+id;
                    else advisories.add(new Advisory(id,Severity.from(detail),detail.path("summary").asText(),"https://osv.dev/vulnerability/"+id,detail));
                }
                if(replay.has("error"))error=replay.get("error").asText();
            }
            heartbeat.run();answers.add(new Answer(c,clock.instant(),"REPLAY",error==null,List.copyOf(advisories),replay,error));
        }
        return answers;
    }
}

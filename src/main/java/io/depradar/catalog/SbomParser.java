package io.depradar.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.depradar.shared.Json;
import io.depradar.shared.Values;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import static io.depradar.shared.Values.require;

@Component
public class SbomParser {
    public record Parsed(String root,List<CatalogMapper.Component> components,List<CatalogMapper.Edge> edges) {}
    private final Json json;
    public SbomParser(Json json) { this.json=json; }
    public Parsed parse(byte[] bytes,String release) {
        require(bytes.length>0 && bytes.length<=10*1024*1024,"清单大小必须为 1 字节至 10 MB");
        JsonNode doc=json.input(bytes);
        require(doc!=null && doc.isObject() && "CycloneDX".equals(doc.path("bomFormat").asText()) && "1.6".equals(doc.path("specVersion").asText()),"只支持 CycloneDX JSON 1.6");
        String root=text(doc.path("metadata").path("component"),"bom-ref",255);
        List<CatalogMapper.Component> components=new ArrayList<>();
        Set<String> refs=new HashSet<>(); refs.add(root);
        require(doc.path("components").isArray(),"components 必须为数组");
        flatten(doc.path("components"),release,components,refs);
        require(!components.isEmpty() && components.size()<=5000,"每个版本须包含 1 至 5000 个 Maven 组件");
        require(doc.path("dependencies").isArray(),"dependencies 必须显式提供");
        Set<CatalogMapper.Edge> edges=new LinkedHashSet<>(); Set<String> declared=new HashSet<>();
        for(JsonNode node:doc.path("dependencies")) {
            String from=text(node,"ref",255);
            require(refs.contains(from) && declared.add(from),"依赖节点未知或重复: "+from);
            if(node.has("dependsOn")) require(node.get("dependsOn").isArray(),"dependsOn 必须为数组");
            for(JsonNode target:node.path("dependsOn")) {
                require(target.isTextual() && refs.contains(target.asText()),"依赖目标未知: "+target);
                require(edges.add(new CatalogMapper.Edge(from,target.asText())),"重复依赖边");
            }
        }
        return new Parsed(root,List.copyOf(components),List.copyOf(edges));
    }
    private void flatten(JsonNode nodes,String release,List<CatalogMapper.Component> result,Set<String> refs) {
        for(JsonNode node:nodes) {
            String ref=text(node,"bom-ref",255),group=text(node,"group",255),name=text(node,"name",255),version=text(node,"version",255),purl=text(node,"purl",1200);
            require(refs.add(ref),"bom-ref 重复: "+ref);
            require(group.matches("[A-Za-z0-9_.-]+") && name.matches("[A-Za-z0-9_.-]+"),"无效 Maven 坐标");
            String expected="pkg:maven/"+group+"/"+name+"@"+version;
            String base=purl.split("[?#]",2)[0];
            try { require(URLDecoder.decode(base.replace("+","%2B"),StandardCharsets.UTF_8).equals(expected),"purl 与 Maven 坐标不符: "+ref); }
            catch(IllegalArgumentException e) { throw io.depradar.shared.Problem.bad("无效 purl: "+ref); }
            result.add(new CatalogMapper.Component(Values.id(),release,ref,group,name,version,purl));
            require(result.size()<=5000,"组件数量超过 5000");
            if(node.has("components")) { require(node.get("components").isArray(),"嵌套 components 必须为数组"); flatten(node.get("components"),release,result,refs); }
        }
    }
    private String text(JsonNode node,String field,int max) {
        JsonNode value=node.path(field);
        require(value.isTextual() && !value.asText().isBlank() && value.asText().length()<=max,"字段缺失或长度无效: "+field);
        return value.asText();
    }
}

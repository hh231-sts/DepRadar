package io.depradar.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化业务数据", e); }
    }
    public JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("保存的 JSON 无法读取", e); }
    }
    public JsonNode input(byte[] value) {
        try { return mapper.readTree(value); }
        catch (java.io.IOException e) { throw Problem.bad("清单不是有效的 JSON: " + e.getMessage()); }
    }
}

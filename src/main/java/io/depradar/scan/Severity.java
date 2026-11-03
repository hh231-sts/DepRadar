package io.depradar.scan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class Severity {
    private static final List<String> LEVELS=List.of("LOW","MODERATE","HIGH","CRITICAL");
    private Severity() {}
    static String from(JsonNode advisory) {
        List<String> labels=new ArrayList<>();
        labels.add(advisory.path("database_specific").path("severity").asText());
        for(JsonNode a:advisory.path("affected")) {
            labels.add(a.path("ecosystem_specific").path("severity").asText());
            labels.add(a.path("database_specific").path("severity").asText());
        }
        int highest=-1;
        for(String label:labels) { String s=label.toUpperCase(Locale.ROOT);if(s.equals("MEDIUM"))s="MODERATE";highest=Math.max(highest,LEVELS.indexOf(s)); }
        for(JsonNode severity:advisory.path("severity")) {
            String vector=severity.path("score").asText();
            if(vector.startsWith("CVSS:3.0/") || vector.startsWith("CVSS:3.1/")) {
                double score=v3(vector);if(score>=0) highest=Math.max(highest,score>=9?3:score>=7?2:score>=4?1:0);
            }
        }
        return highest<0?"UNKNOWN":LEVELS.get(highest);
    }
    private static double v3(String vector) {
        Map<String,String> m=new HashMap<>();for(String part:vector.split("/")) { String[] pair=part.split(":",2);if(pair.length==2)m.put(pair[0],pair[1]); }
        boolean changed="C".equals(m.get("S"));
        if(!Set.of("U","C").contains(m.getOrDefault("S",""))) return -1;
        double av=metric(m,"AV",Map.of("N",.85,"A",.62,"L",.55,"P",.2));
        double ac=metric(m,"AC",Map.of("L",.77,"H",.44));
        double pr=metric(m,"PR",Map.of("N",.85,"L",changed?.68:.62,"H",changed?.5:.27));
        double ui=metric(m,"UI",Map.of("N",.85,"R",.62));
        double c=metric(m,"C",Map.of("N",0.,"L",.22,"H",.56)),i=metric(m,"I",Map.of("N",0.,"L",.22,"H",.56)),a=metric(m,"A",Map.of("N",0.,"L",.22,"H",.56));
        if(Math.min(Math.min(Math.min(av,ac),Math.min(pr,ui)),Math.min(c,Math.min(i,a)))<0) return -1;
        double iss=1-(1-c)*(1-i)*(1-a),impact=changed?7.52*(iss-.029)-3.25*Math.pow(iss-.02,15):6.42*iss;
        if(impact<=0)return 0;
        return Math.ceil((Math.min((impact+8.22*av*ac*pr*ui)*(changed?1.08:1),10)-1e-10)*10)/10;
    }
    private static double metric(Map<String,String> m,String key,Map<String,Double> weights) { return weights.getOrDefault(m.getOrDefault(key,""),-1.); }
}

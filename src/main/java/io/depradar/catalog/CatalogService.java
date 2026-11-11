package io.depradar.catalog;

import io.depradar.identity.Access;
import io.depradar.shared.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final CatalogMapper db; private final Access access; private final SbomParser parser; private final StringRedisTemplate redis; private final Json json;
    private final RedisAvailability availability;
    public CatalogService(CatalogMapper db,Access access,SbomParser parser,StringRedisTemplate redis,Json json,RedisAvailability availability) { this.db=db;this.access=access;this.parser=parser;this.redis=redis;this.json=json;this.availability=availability; }
    @Transactional public CatalogMapper.Project createProject(String team,String name) {
        access.team(team,Access.Right.WRITE);String id=Values.id();db.addProject(id,team,name);db.defaultPolicy(id,access.human());return db.project(id);
    }
    public Values.Page<CatalogMapper.Project> projects(String team,String cursor) { access.team(team,Access.Right.READ);return Values.page(db.projects(team,cursor),CatalogMapper.Project::id); }
    public CatalogMapper.Project project(String id) { return access.project(id,Access.Right.READ); }
    public Values.Page<CatalogMapper.Release> releases(String project,String cursor) { access.project(project,Access.Right.READ);return Values.page(db.releases(project,cursor),CatalogMapper.Release::id); }
    public CatalogMapper.Release release(String id) { return access.release(id,Access.Right.READ); }
    @Transactional public CatalogMapper.Release upload(String project,String tag,byte[] bytes,String key) {
        access.project(project,Access.Right.WRITE);db.lockProject(project);
        String hash=Values.hash(bytes),requestHash=Values.hash(tag+"\n"+hash);
        String remembered=replay(project,"IMPORT",key,requestHash);if(remembered!=null) return db.release(remembered);
        var tagged=db.byTag(project,tag);
        if(tagged!=null && !tagged.sbomHash().equals(hash)) throw Problem.conflict("该标签已绑定不同内容；请登记新版本");
        var existing=db.byHash(project,hash);
        if(existing!=null) { remember(project,"IMPORT",key,requestHash,existing.id()); return existing; }
        String id=Values.id();var parsed=parser.parse(bytes,id);
        db.addRelease(id,project,tag,hash,bytes,parsed.root());
        parsed.components().forEach(db::addComponent);parsed.edges().forEach(e -> db.addEdge(id,e.fromRef(),e.toRef()));
        remember(project,"IMPORT",key,requestHash,id);return db.release(id);
    }
    public byte[] raw(String release) { access.release(release,Access.Right.READ);return db.raw(release).rawSbom(); }
    public Values.Page<CatalogMapper.Component> components(String release,String cursor) { access.release(release,Access.Right.READ);return Values.page(db.componentPage(release,cursor),CatalogMapper.Component::id); }
    public Object graph(String release,Runnable onRedisUnavailable) {
        String missingKey="depradar:release:missing:"+release;
        if(Boolean.TRUE.equals(readCache(()->redis.hasKey(missingKey),onRedisUnavailable))) throw Problem.missing("版本不存在");
        var r=db.release(release);
        if(r==null) {
            writeCache(missingKey,"1",Duration.ofSeconds(30));
            throw Problem.missing("版本不存在");
        }
        access.project(r.projectId(),Access.Right.READ);
        String key="depradar:graph:"+release+":"+r.sbomHash();
        String cached=readCache(()->redis.opsForValue().get(key),onRedisUnavailable);if(cached!=null) return json.read(cached);
        var graph=Map.of("root",r.rootRef(),"components",db.components(release),"edges",db.edges(release));
        writeCache(key,json.write(graph),Duration.ofHours(1));return graph;
    }
    private <T> T readCache(Supplier<T> read,Runnable onRedisUnavailable) {
        try {return availability.execute(read);}
        catch(RedisAvailability.Unavailable e) {onRedisUnavailable.run();return null;}
    }
    private void writeCache(String key,String value,Duration ttl) {
        try {availability.execute(()->{redis.opsForValue().set(key,value,ttl);return null;});}
        catch(RedisAvailability.Unavailable e) { /* The database result is already available. */ }
    }
    public String replay(String project,String operation,String key,String hash) {
        if(key==null) return null;
        Values.require(!key.isBlank() && key.length()<=120,"幂等键长度必须为 1 至 120");
        var old=db.idempotency(project,operation,key);
        if(old==null) return null;
        if(!old.requestHash().equals(hash)) throw Problem.conflict("幂等键已用于不同请求");
        return old.resourceId();
    }
    public void remember(String project,String operation,String key,String hash,String resource) { if(key!=null) db.remember(project,operation,key,hash,resource); }
    public Map<String,List<String>> paths(String release) {
        String root=db.release(release).rootRef();
        Map<String,List<String>> outgoing=new HashMap<>(),paths=new HashMap<>();
        for(var edge:db.edges(release)) outgoing.computeIfAbsent(edge.fromRef(),k->new ArrayList<>()).add(edge.toRef());
        ArrayDeque<String> queue=new ArrayDeque<>();queue.add(root);paths.put(root,List.of(root));
        while(!queue.isEmpty()) {
            String ref=queue.remove();
            for(String next:outgoing.getOrDefault(ref,List.of())) if(!paths.containsKey(next)) {
                List<String> path=new ArrayList<>(paths.get(ref));path.add(next);paths.put(next,List.copyOf(path));queue.add(next);
            }
        }
        return paths;
    }
}

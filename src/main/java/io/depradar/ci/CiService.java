package io.depradar.ci;

import io.depradar.identity.Access;
import io.depradar.governance.GovernanceMapper;
import io.depradar.shared.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CiService {
    private final CiMapper db;private final Access access;private final GovernanceMapper audit;private final Json json;
    public CiService(CiMapper db,Access access,GovernanceMapper audit,Json json) { this.db=db;this.access=access;this.audit=audit;this.json=json; }
    public List<CiMapper.Credential> list(String project) { access.project(project,Access.Right.ADMIN);return db.list(project); }
    @Transactional public Map<String,String> create(String project,String name) {
        access.project(project,Access.Right.ADMIN);byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token="dr_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),id=Values.id();
        db.create(id,project,name,Values.hash(token),access.human());
        audit.audit(Values.id(),project,access.human(),"CI_CREDENTIAL_CREATED",id,json.write(Map.of("name",name)));
        return Map.of("id",id,"token",token);
    }
    @Transactional public void revoke(String project,String id) {
        access.project(project,Access.Right.ADMIN);
        if(db.revoke(project,id)!=1) throw Problem.conflict("凭据不存在或已吊销");
        audit.audit(Values.id(),project,access.human(),"CI_CREDENTIAL_REVOKED",id,"{}");
    }
}

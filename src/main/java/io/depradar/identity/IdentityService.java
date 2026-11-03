package io.depradar.identity;

import io.depradar.shared.Values;
import io.depradar.shared.Problem;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final IdentityMapper db; private final Access access;
    public IdentityService(IdentityMapper db,Access access) { this.db=db;this.access=access; }
    public List<IdentityMapper.Operator> operators() { access.human();return db.operators(); }
    @Transactional
    public String createTeam(String name) {
        String user=access.human(),id=Values.id(); db.addTeam(id,name);db.addMember(id,user,"OWNER"); return id;
    }
    public List<IdentityMapper.Team> teams() { return db.teams(access.human()); }
    public List<IdentityMapper.Member> members(String team) { access.team(team,Access.Right.READ);return db.members(team); }
    @Transactional
    public void member(String team,String name,String role) {
        access.team(team,Access.Right.ADMIN);
        Values.require(Set.of("OWNER","MAINTAINER","REVIEWER","VIEWER").contains(role),"无效成员角色");
        db.lockTeam(team);
        var user=db.byName(name);
        if(user==null) { user=new IdentityMapper.Operator(Values.id(),name);db.addOperator(user); }
        String old=db.role(team,user.id());
        if("OWNER".equals(old) && !"OWNER".equals(role) && db.owners(team)==1) throw Problem.conflict("团队必须保留一位 OWNER");
        if(old==null) db.addMember(team,user.id(),role); else db.changeRole(team,user.id(),role);
    }
}

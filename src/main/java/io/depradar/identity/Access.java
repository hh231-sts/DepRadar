package io.depradar.identity;

import io.depradar.catalog.CatalogMapper;
import io.depradar.shared.Problem;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Access {
    public enum Right { READ, WRITE, REVIEW, ADMIN, GATE }
    private final IdentityMapper identities;
    private final CatalogMapper catalog;
    public Access(IdentityMapper identities, CatalogMapper catalog) { this.identities=identities; this.catalog=catalog; }
    public Actor actor() {
        Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Actor actor)) throw Problem.forbidden();
        return actor;
    }
    public String human() { Actor a=actor(); if(a.ci()) throw Problem.forbidden(); return a.userId(); }
    public void team(String team, Right right) {
        String role=identities.role(team,human());
        boolean allowed=role != null && switch(right) {
            case READ, GATE -> true;
            case WRITE -> Set.of("OWNER","MAINTAINER").contains(role);
            case REVIEW -> Set.of("OWNER","REVIEWER").contains(role);
            case ADMIN -> role.equals("OWNER");
        };
        if(!allowed) throw Problem.forbidden();
    }
    public CatalogMapper.Project project(String id, Right right) {
        var project=catalog.project(id);
        if(project==null) throw Problem.missing("项目不存在");
        Actor a=actor();
        if(a.ci()) {
            if(!id.equals(a.projectId()) || (right!=Right.READ && right!=Right.WRITE && right!=Right.GATE)) throw Problem.forbidden();
        } else team(project.teamId(),right);
        return project;
    }
    public CatalogMapper.Release release(String id, Right right) {
        var release=catalog.release(id);
        if(release==null) throw Problem.missing("版本不存在");
        project(release.projectId(),right);
        return release;
    }
}

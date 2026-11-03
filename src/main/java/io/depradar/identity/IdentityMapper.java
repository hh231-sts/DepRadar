package io.depradar.identity;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IdentityMapper {
    record Operator(String id, String name) {}
    record Team(String id, String name, String role) {}
    record Member(String userId, String name, String role) {}
    @Select("SELECT id,name FROM operators WHERE name=#{name}") Operator byName(String name);
    @Select("SELECT id,name FROM operators WHERE id=#{id}") Operator operator(String id);
    @Select("SELECT id,name FROM operators ORDER BY name,id") List<Operator> operators();
    @Insert("INSERT INTO operators(id,name) VALUES(#{id},#{name})") void addOperator(Operator operator);
    @Insert("INSERT INTO teams(id,name) VALUES(#{id},#{name})") void addTeam(String id,String name);
    @Insert("INSERT INTO memberships(team_id,user_id,role) VALUES(#{team},#{user},#{role})") void addMember(String team,String user,String role);
    @Select("SELECT t.id,t.name,m.role FROM teams t JOIN memberships m ON m.team_id=t.id WHERE m.user_id=#{user} ORDER BY t.id") List<Team> teams(String user);
    @Select("SELECT role FROM memberships WHERE team_id=#{team} AND user_id=#{user}") String role(String team,String user);
    @Select("SELECT m.user_id,o.name,m.role FROM memberships m JOIN operators o ON o.id=m.user_id WHERE m.team_id=#{team} ORDER BY o.name") List<Member> members(String team);
    @Update("UPDATE memberships SET role=#{role} WHERE team_id=#{team} AND user_id=#{user}") int changeRole(String team,String user,String role);
    @Select("SELECT id FROM teams WHERE id=#{id} FOR UPDATE") String lockTeam(String id);
    @Select("SELECT COUNT(*) FROM memberships WHERE team_id=#{team} AND role='OWNER'") int owners(String team);
}

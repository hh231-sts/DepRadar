package io.depradar.identity;

import io.depradar.ci.CiMapper;
import io.depradar.shared.Values;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,CiMapper ci,IdentityMapper identities) throws Exception {
        RequestMatcher bearer = r -> r.getRequestURI().startsWith("/api/v1/") && r.getHeader("Authorization")!=null;
        var csrf=new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        http.authorizeHttpRequests(a -> a.requestMatchers("/assets/**","/actuator/health/**").permitAll()
            .requestMatchers("/actuator/prometheus").access((authentication,context)-> {
                Object principal=authentication.get().getPrincipal();
                boolean owner=principal instanceof Actor actor && !actor.ci() && identities.teams(actor.userId()).stream().anyMatch(t->t.role().equals("OWNER"));
                return new org.springframework.security.authorization.AuthorizationDecision(owner);
            })
            .requestMatchers("/actuator/**").denyAll().anyRequest().authenticated())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.csrfTokenRepository(csrf).ignoringRequestMatchers(bearer))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> json(res,401,"操作人或 CI 凭据无效"))
                .accessDeniedHandler((req,res,ex) -> json(res,403,"权限不足或 CSRF 凭据无效")))
            // Establish the local operation context after session handling so each request does not reset its CSRF cookie.
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected boolean shouldNotFilter(HttpServletRequest req) {
                    return req.getRequestURI().startsWith("/assets/") || req.getRequestURI().startsWith("/actuator/health");
                }
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    Actor actor;
                    if(bearer.matches(req)) {
                        String header=req.getHeader("Authorization");
                        var credential=header.startsWith("Bearer ") ? ci.byHash(Values.hash(header.substring(7))) : null;
                        if(credential==null) { json(res,401,"CI 凭据无效或已吊销");return; }
                        actor=new Actor(null,"CI: "+credential.name(),credential.projectId());
                    } else {
                        String id=LocalIdentity.DEFAULT_OPERATOR;
                        if(req.getCookies()!=null)for(Cookie cookie:req.getCookies())if(cookie.getName().equals(LocalIdentity.COOKIE))id=cookie.getValue();
                        var operator=identities.operator(id);
                        if(operator==null) { json(res,400,"操作人不存在，请清除工作空间操作人选择后重试");return; }
                        actor=new Actor(operator.id(),operator.name(),null);
                    }
                    var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new PreAuthenticatedAuthenticationToken(actor,null,List.of()));
                    SecurityContextHolder.setContext(context);
                    chain.doFilter(req,res);
                }
            },SessionManagementFilter.class);
        return http.build();
    }
    private static void json(HttpServletResponse res,int status,String reason) throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"error\":\""+reason+"\"}");
    }
}

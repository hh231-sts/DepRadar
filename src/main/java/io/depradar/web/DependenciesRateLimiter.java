package io.depradar.web;

import io.depradar.identity.Access;
import io.depradar.shared.Problem;
import io.depradar.shared.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DependenciesRateLimiter {
    private static final DefaultRedisScript<Long> TOKEN_BUCKET=new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
        local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(bucket[1]) or 20
        local updated = tonumber(bucket[2]) or now
        tokens = math.min(20, tokens + (now - updated) * 10)
        local allowed = 0
        if tokens >= 1 then
            tokens = tokens - 1
            allowed = 1
        end
        redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
        redis.call('EXPIRE', KEYS[1], 2)
        return allowed
        """,Long.class);
    private final StringRedisTemplate redis;
    private final Access access;

    public DependenciesRateLimiter(StringRedisTemplate redis,Access access) {this.redis=redis;this.access=access;}

    public void check(HttpServletRequest request) {
        var actor=access.actor();
        String caller=actor.ci() ? "ci:"+Values.hash(request.getHeader("Authorization").substring(7)) : "user:"+actor.userId();
        consume("ip:"+request.getRemoteAddr());
        consume(caller);
    }

    private void consume(String caller) {
        if(redis.execute(TOKEN_BUCKET,List.of("depradar:rate:dependencies:"+caller))==0L)
            throw new Problem(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请稍后重试");
    }
}

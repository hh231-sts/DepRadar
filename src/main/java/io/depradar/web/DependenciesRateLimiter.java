package io.depradar.web;

import io.depradar.identity.Access;
import io.depradar.shared.Problem;
import io.depradar.shared.RedisAvailability;
import io.depradar.shared.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.*;
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
    private final RedisAvailability availability;
    private static final long IDLE_NANOS=Duration.ofSeconds(2).toNanos();
    private final Map<String,Bucket> localBuckets=new HashMap<>();
    private final Bucket instanceBucket=new Bucket(System.nanoTime());

    public DependenciesRateLimiter(StringRedisTemplate redis,Access access,RedisAvailability availability) {this.redis=redis;this.access=access;this.availability=availability;}

    public Runnable check(HttpServletRequest request) {
        var actor=access.actor();
        String caller=actor.ci() ? "ci:"+Values.hash(request.getHeader("Authorization").substring(7)) : "user:"+actor.userId();
        String ip="ip:"+request.getRemoteAddr();
        Runnable fallback=new Runnable() {
            private boolean checked;
            @Override public void run() {
                if(!checked) {consumeLocal(ip,caller);checked=true;}
            }
        };
        try {consume(ip);consume(caller);}
        catch(RedisAvailability.Unavailable e) {fallback.run();}
        return fallback;
    }

    private void consume(String caller) {
        if(availability.execute(()->redis.execute(TOKEN_BUCKET,List.of("depradar:rate:dependencies:"+caller)))==0L)
            throw limited();
    }

    private synchronized void consumeLocal(String ip,String caller) {
        long now=System.nanoTime();
        localBuckets.values().removeIf(bucket->now-bucket.updated>=IDLE_NANOS);
        instanceBucket.consume(now);
        localBuckets.computeIfAbsent(ip,key->new Bucket(now)).consume(now);
        localBuckets.computeIfAbsent(caller,key->new Bucket(now)).consume(now);
    }

    private static Problem limited() {return new Problem(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请稍后重试");}

    private static class Bucket {
        private double tokens=20;
        private long updated;
        private Bucket(long now) {updated=now;}
        private void consume(long now) {
            tokens=Math.min(20,tokens+(now-updated)*10.0/1_000_000_000);
            updated=now;
            if(tokens<1) throw limited();
            tokens--;
        }
    }
}

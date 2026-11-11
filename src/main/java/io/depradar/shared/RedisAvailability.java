package io.depradar.shared;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

@Component
public class RedisAvailability {
    private static final Logger log=LoggerFactory.getLogger(RedisAvailability.class);
    private static final long COOLDOWN_NANOS=Duration.ofSeconds(5).toNanos();
    private record State(long retryAt,boolean probing) {}
    private final AtomicReference<State> state=new AtomicReference<>();

    public <T> T execute(Supplier<T> operation) {
        State attempt=state.get();
        if(attempt!=null) {
            if(attempt.probing() || System.nanoTime()-attempt.retryAt()<0) throw new Unavailable(null);
            State probe=new State(0,true);
            if(!state.compareAndSet(attempt,probe)) throw new Unavailable(null);
            attempt=probe;
        }
        try {
            T result=operation.get();
            if(attempt!=null && state.compareAndSet(attempt,null)) log.info("Redis 业务调用已恢复");
            return result;
        } catch(RedisConnectionFailureException | QueryTimeoutException e) {
            if(state.compareAndSet(attempt,new State(System.nanoTime()+COOLDOWN_NANOS,false)) && attempt==null)
                log.warn("Redis 不可用，业务调用冷却 5 秒",e);
            throw new Unavailable(e);
        } finally {
            // Release the recovery probe even when an unrelated exception propagates.
            if(attempt!=null) state.compareAndSet(attempt,new State(System.nanoTime()+COOLDOWN_NANOS,false));
        }
    }

    public static class Unavailable extends RuntimeException {
        private Unavailable(Throwable cause) {super("Redis 暂不可用",cause);}
    }
}

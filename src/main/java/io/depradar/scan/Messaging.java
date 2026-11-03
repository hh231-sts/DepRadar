package io.depradar.scan;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class Messaging {
    public static final String QUEUE="depradar.scan";
    @Bean Queue scanQueue() { return QueueBuilder.durable(QUEUE).withArgument("x-dead-letter-exchange","depradar.dead").build(); }
    @Bean DirectExchange deadExchange() { return new DirectExchange("depradar.dead"); }
    @Bean Queue deadQueue() { return QueueBuilder.durable("depradar.scan.dead").build(); }
    @Bean Binding deadBinding(Queue deadQueue,DirectExchange deadExchange) { return BindingBuilder.bind(deadQueue).to(deadExchange).with(QUEUE); }

    @org.springframework.stereotype.Component
    public static class Dispatcher {
        private static final Logger log=LoggerFactory.getLogger(Dispatcher.class);
        private final ScanMapper db;private final RabbitTemplate mq;private final Clock clock;private final ScanJobs jobs;private final MeterRegistry metrics;
        @Value("${depradar.scheduling}") private boolean enabled;
        public Dispatcher(ScanMapper db,RabbitTemplate mq,Clock clock,ScanJobs jobs,MeterRegistry metrics) {this.db=db;this.mq=mq;this.clock=clock;this.jobs=jobs;this.metrics=metrics;}
        @Scheduled(fixedDelay=1000) public void publish() {
            if(!enabled)return;
            publishPending();
        }
        public void publishPending() {
            for(var event:db.events(clock.instant())) {
                try {
                    var correlation=new CorrelationData(event.id());
                    mq.convertAndSend("",QUEUE,event.batchId(),correlation);
                    var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                    if(!confirm.isAck() || correlation.getReturned()!=null)throw new IllegalStateException("RabbitMQ 未确认或无法路由消息");
                    db.sent(event.id(),clock.instant());
                } catch(Exception e) {
                    if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                    db.sendFailed(event.id(),clock.instant().plusSeconds(Math.min(60,5L*(event.attempts()+1))),e.getMessage());
                    metrics.counter("depradar.outbox.publish.failures").increment();log.warn("Outbox 投递失败 {}: {}",event.id(),e.getMessage());
                }
            }
        }
        @Scheduled(fixedDelay=15000) public void recover() {
            if(!enabled)return;for(String id:db.recoverable(clock.instant(),clock.instant().minusSeconds(30)))jobs.recover(id);
        }
    }
    @org.springframework.stereotype.Component
    public static class Worker {
        private static final Logger log=LoggerFactory.getLogger(Worker.class);
        private final ScanJobs jobs;private final VulnerabilitySource source;
        public Worker(ScanJobs jobs,VulnerabilitySource source) {this.jobs=jobs;this.source=source;}
        @RabbitListener(queues=QUEUE) public void accept(String batch) {
            var lease=jobs.claim(batch);if(lease==null)return;
            java.util.List<VulnerabilitySource.Answer> answers;
            try { answers=source.query(lease.components(),()->jobs.renew(lease)); }
            catch(ScanJobs.LostLease e) { return; }
            catch(RuntimeException e) {log.error("扫描来源处理失败 {}",batch,e);answers=jobs.failures(lease.components(),e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            jobs.complete(lease,answers);
        }
    }
}

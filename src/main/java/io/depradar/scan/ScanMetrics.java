package io.depradar.scan;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class ScanMetrics implements MeterBinder {
    private final ScanMapper db;
    public ScanMetrics(ScanMapper db) {this.db=db;}
    @Override public void bindTo(MeterRegistry registry) {
        Gauge.builder("depradar.scans.active",db,ScanMapper::activeTotal).description("Queued and running scans").register(registry);
        Gauge.builder("depradar.outbox.pending",db,ScanMapper::pendingEvents).description("Events awaiting broker confirmation").register(registry);
    }
}

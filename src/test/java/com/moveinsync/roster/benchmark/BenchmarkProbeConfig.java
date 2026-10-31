package com.moveinsync.roster.benchmark;

import com.moveinsync.roster.shared.FailureProbe;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** No Mockito invocation retention in measured production paths. */
@TestConfiguration(proxyBeanMethods=false)
public class BenchmarkProbeConfig {
    @Bean @Primary Probe benchmarkProbe() { return new Probe(); }
    public static class Probe implements FailureProbe {
        volatile Consumer<String> observer=point->{};
        @Override public void at(String point) { observer.accept(point); }
    }
}

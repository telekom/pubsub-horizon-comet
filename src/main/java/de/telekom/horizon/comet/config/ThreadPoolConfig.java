package de.telekom.horizon.comet.config;

import de.telekom.horizon.comet.service.BoundedScheduledExecutorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class ThreadPoolConfig {

    @Autowired
    private CometConfig cometConfig;

    @Autowired
    private MeterRegistry meterRegistry;

    @Bean(name = "deliveryTaskExecutor")
    public ThreadPoolTaskExecutor deliveryTaskExecutor() {
        ThreadPoolTaskExecutor deliveryTaskExecutor = new ThreadPoolTaskExecutor();

        deliveryTaskExecutor.setAwaitTerminationSeconds(20);
        deliveryTaskExecutor.setCorePoolSize(cometConfig.getConsumerThreadPoolSize());
        deliveryTaskExecutor.setMaxPoolSize(cometConfig.getConsumerThreadPoolSize());
        deliveryTaskExecutor.setQueueCapacity(cometConfig.getConsumerQueueCapacity());

        deliveryTaskExecutor.afterPropertiesSet();
        ExecutorServiceMetrics.monitor(meterRegistry, deliveryTaskExecutor.getThreadPoolExecutor(), "deliveryTaskExecutor", Collections.emptyList());

        return deliveryTaskExecutor;
    }

    @Bean(name = "redeliveryExecutorService")
    public ScheduledExecutorService redeliveryScheduledExecutorService() {
        ScheduledThreadPoolExecutor redeliveryTaskExecutor = new ScheduledThreadPoolExecutor(
                cometConfig.getRedeliveryThreadPoolSize(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        redeliveryTaskExecutor.setRemoveOnCancelPolicy(true);
        redeliveryTaskExecutor.allowCoreThreadTimeOut(true);
        redeliveryTaskExecutor.setKeepAliveTime(20, java.util.concurrent.TimeUnit.SECONDS);

        redeliveryTaskExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        redeliveryTaskExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        BoundedScheduledExecutorService boundedExecutor = new BoundedScheduledExecutorService(redeliveryTaskExecutor,
                cometConfig.getRedeliveryQueueCapacity());

        ExecutorServiceMetrics.monitor(meterRegistry, redeliveryTaskExecutor, "redeliveryTaskExecutor", Collections.emptyList());

        return boundedExecutor;
    }

}

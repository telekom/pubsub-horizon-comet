// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class BoundedScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledThreadPoolExecutor delegate;
    private final int maxQueueSize;

    private final Counter rejectedCounter;

    public BoundedScheduledExecutorService(ScheduledThreadPoolExecutor delegate, int maxQueueSize, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.maxQueueSize = maxQueueSize;

        this.rejectedCounter = Counter.builder("pubsub.threadpool.saturated")
                .tag("name", "redelivery")
                .description("Total number of rejected tasks for redelivery")
                .register(meterRegistry);
    }

    private int getQueuedTaskCount() {
        return delegate.getQueue().size();
    }

    private void checkQueueCapacity() {
        if (getQueuedTaskCount() >= maxQueueSize) {
            rejectedCounter.increment();
            throw new RejectedExecutionException("Queue capacity limit reached: " + maxQueueSize);
        }
    }

    @NonNull
    @Override
    public ScheduledFuture<?> schedule(@NonNull Runnable runnable, long delay, @NonNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.schedule(runnable, delay, timeUnit);
    }

    @NonNull
    @Override
    public <V> ScheduledFuture<V> schedule(@NonNull Callable<V> callable, long delay,
                                           @NonNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.schedule(callable, delay, timeUnit);
    }

    @NonNull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable runnable, long initialDelay, long period,
                                                  @NonNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.scheduleAtFixedRate(runnable, initialDelay, period, timeUnit);
    }

    @NonNull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable runnable, long initialDelay, long delay,
                                                     @NonNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.scheduleWithFixedDelay(runnable, initialDelay, delay, timeUnit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @NonNull
    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit timeUnit) throws InterruptedException {
        return delegate.awaitTermination(timeout, timeUnit);
    }

    @NonNull
    @Override
    public <T> Future<T> submit(@NonNull Callable<T> callable) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NonNull
    @Override
    public <T> Future<T> submit(@NonNull Runnable runnable, T result) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NonNull
    @Override
    public Future<?> submit(@NonNull Runnable runnable) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NonNull
    @Override
    public <T> List<Future<T>> invokeAll(@NonNull Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NonNull
    @Override
    public <T> List<Future<T>> invokeAll(@NonNull Collection<? extends Callable<T>> tasks, long timeout,
                                         @NonNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NonNull
    @Override
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks, long timeout,
                           @NonNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void execute(@NonNull Runnable runnable) {
        checkQueueCapacity();
        delegate.execute(runnable);
    }

}

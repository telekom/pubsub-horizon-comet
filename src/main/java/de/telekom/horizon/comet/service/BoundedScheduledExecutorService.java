// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;

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

    @NotNull
    @Override
    public ScheduledFuture<?> schedule(@NotNull Runnable runnable, long delay, @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.schedule(runnable, delay, timeUnit);
    }

    @NotNull
    @Override
    public <V> ScheduledFuture<V> schedule(@NotNull Callable<V> callable, long delay,
                                           @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.schedule(callable, delay, timeUnit);
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NotNull Runnable runnable, long initialDelay, long period,
                                                  @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.scheduleAtFixedRate(runnable, initialDelay, period, timeUnit);
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable runnable, long initialDelay, long delay,
                                                     @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return delegate.scheduleWithFixedDelay(runnable, initialDelay, delay, timeUnit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @NotNull
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
    public boolean awaitTermination(long timeout, @NotNull TimeUnit timeUnit) throws InterruptedException {
        return delegate.awaitTermination(timeout, timeUnit);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Callable<T> callable) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Runnable runnable, T result) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull Runnable runnable) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks, long timeout,
                                         @NotNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Not supported");
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks, long timeout,
                           @NotNull TimeUnit timeUnit) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        checkQueueCapacity();
        delegate.execute(runnable);
    }

}

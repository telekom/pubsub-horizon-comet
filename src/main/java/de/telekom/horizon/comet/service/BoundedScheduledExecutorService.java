package de.telekom.horizon.comet.service;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BoundedScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;
    private final int maxQueueSize;
    private final AtomicInteger queuedTaskCount = new AtomicInteger(0);

    public BoundedScheduledExecutorService(ScheduledExecutorService delegate, int maxQueueSize) {
        this.delegate = delegate;
        this.maxQueueSize = maxQueueSize;
    }

    private void checkQueueCapacity() {
        if (queuedTaskCount.get() >= maxQueueSize) {
            throw new RejectedExecutionException("Queue capacity limit reached: " + maxQueueSize);
        }
    }

    private <V> ScheduledFuture<V> trackCompletion(ScheduledFuture<V> future) {
        // Increment counter when task is submitted
        queuedTaskCount.incrementAndGet();

        // Decrement counter when task completes or is canceled
        return new ScheduledFutureWrapper<>(future, queuedTaskCount::decrementAndGet);
    }

    @NotNull
    @Override
    public ScheduledFuture<?> schedule(@NotNull Runnable runnable, long delay, @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return trackCompletion(delegate.schedule(runnable, delay, timeUnit));
    }

    @NotNull
    @Override
    public <V> ScheduledFuture<V> schedule(@NotNull Callable<V> callable, long delay, @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return trackCompletion(delegate.schedule(callable, delay, timeUnit));
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(@NotNull Runnable runnable, long initialDelay, long period, @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return trackCompletion(delegate.scheduleAtFixedRate(runnable, initialDelay, period, timeUnit));
    }

    @NotNull
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable runnable, long initialDelay, long delay, @NotNull TimeUnit timeUnit) {
        checkQueueCapacity();
        return trackCompletion(delegate.scheduleWithFixedDelay(runnable, initialDelay, delay, timeUnit));
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
        checkQueueCapacity();
        Future<T> future = delegate.submit(callable);
        queuedTaskCount.incrementAndGet();
        return new FutureWrapper<>(future, queuedTaskCount::decrementAndGet);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Runnable runnable, T result) {
        checkQueueCapacity();
        Future<T> future = delegate.submit(runnable, result);
        queuedTaskCount.incrementAndGet();
        return new FutureWrapper<>(future, queuedTaskCount::decrementAndGet);
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull Runnable runnable) {
        checkQueueCapacity();
        Future<?> future = delegate.submit(runnable);
        queuedTaskCount.incrementAndGet();
        return new FutureWrapper<>(future, queuedTaskCount::decrementAndGet);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        // Not tracking these as they execute immediately
        return delegate.invokeAll(tasks);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit timeUnit) throws InterruptedException {
        // Not tracking these as they execute immediately
        return delegate.invokeAll(tasks, timeout, timeUnit);
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, timeUnit);
    }

    @Override
    public void execute(@NotNull Runnable runnable) {
        checkQueueCapacity();
        queuedTaskCount.incrementAndGet();
        delegate.execute(() -> {
            try {
                runnable.run();
            } finally {
                queuedTaskCount.decrementAndGet();
            }
        });
    }


    /**
     * Wrapper for ScheduledFuture that decrements counter on completion or cancellation
     */
    private static class ScheduledFutureWrapper<V> implements ScheduledFuture<V> {
        private final ScheduledFuture<V> delegate;
        private final Runnable onDone;

        public ScheduledFutureWrapper(ScheduledFuture<V> delegate, Runnable onDone) {
            this.delegate = delegate;
            this.onDone = onDone;
        }

        @Override
        public long getDelay(@NotNull TimeUnit unit) {
            return delegate.getDelay(unit);
        }

        @Override
        public int compareTo(@NotNull Delayed o) {
            return delegate.compareTo(o);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = delegate.cancel(mayInterruptIfRunning);
            if (result) {
                onDone.run();
            }
            return result;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            try {
                return delegate.get();
            } finally {
                if (delegate.isDone()) {
                    onDone.run();
                }
            }
        }

        @Override
        public V get(long timeout, @NotNull TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return delegate.get(timeout, unit);
            } finally {
                if (delegate.isDone()) {
                    onDone.run();
                }
            }
        }
    }

    /**
     * Wrapper for Future that decrements counter on completion or cancellation
     */
    private static class FutureWrapper<V> implements Future<V> {
        private final Future<V> delegate;
        private final Runnable onDone;

        public FutureWrapper(Future<V> delegate, Runnable onDone) {
            this.delegate = delegate;
            this.onDone = onDone;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = delegate.cancel(mayInterruptIfRunning);
            if (result) {
                onDone.run();
            }
            return result;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            try {
                return delegate.get();
            } finally {
                if (delegate.isDone()) {
                    onDone.run();
                }
            }
        }

        @Override
        public V get(long timeout, @NotNull TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return delegate.get(timeout, unit);
            } finally {
                if (delegate.isDone()) {
                    onDone.run();
                }
            }
        }
    }

}

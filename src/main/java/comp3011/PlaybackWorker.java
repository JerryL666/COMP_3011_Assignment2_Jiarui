/*
 * Author: Jiarui Li
 * Developed with assistance from OpenAI Codex.
 */
package comp3011;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs playback operations in order on one background thread.
 * This keeps decoder access and playback state on the same thread.
 */
public class PlaybackWorker {
    private final ScheduledExecutorService executor;
    private final Consumer<Exception> errorHandler;

    private ScheduledFuture<?> updateTask;
    private boolean closed;

    public PlaybackWorker(Consumer<Exception> errorHandler) {
        this.errorHandler = errorHandler;
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void submit(Runnable action) {
        if (closed) {
            return;
        }

        executor.execute(() -> runSafely(action));
    }

    // Call from the playback thread to begin regular playback updates.
    public void startUpdates(Runnable update) {
        stopUpdates();

        updateTask = executor.scheduleWithFixedDelay(
                () -> runSafely(update),
                0,
                4,
                TimeUnit.MILLISECONDS);
    }

    // Call from the playback thread.
    public void stopUpdates() {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
    }

    public synchronized void shutdown(Runnable cleanup) {
        if (closed) {
            return;
        }

        closed = true;

        // Finish earlier commands before releasing playback resources.
        executor.execute(() -> {
            try {
                stopUpdates();
                runSafely(cleanup);
            } finally {
                executor.shutdown();
            }
        });
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception exception) {
            errorHandler.accept(exception);
        }
    }
}
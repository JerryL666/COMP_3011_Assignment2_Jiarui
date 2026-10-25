/*
 * Author: Jiarui Li
 * Developed with assistance from OpenAI Codex.
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Connects consecutive groups of effects using bounded queues.
 * Each processor belongs to exactly one stage.
 */
public class FramePipeline {
    private static final int QUEUE_CAPACITY = 2;

    private final List<BlockingQueue<FrameTask>> queues;
    private final List<FrameProcessingStage> stages;
    private final List<Thread> workerThreads;
    private boolean started;
    private boolean closed;
    
    public FramePipeline(
            List<FrameProcessor> processors,
            InfoVideo videoInfo) {
        queues = new ArrayList<>();
        stages = new ArrayList<>();
        workerThreads = new ArrayList<>();

        int availableCores = Runtime.getRuntime().availableProcessors();
        int maximumStages = Math.max(1, availableCores - 2);
        int stageCount = Math.min(processors.size(), maximumStages);

        // Each stage needs an input queue and an output queue.
        for (int index = 0; index <= stageCount; index++) {
            queues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        }

        for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
            int startIndex = stageIndex * processors.size() / stageCount;
            int endIndex = (stageIndex + 1) * processors.size() / stageCount;

            // Keep adjacent effects together to preserve command-line order.
            List<FrameProcessor> stageProcessors =
                    new ArrayList<>(processors.subList(startIndex, endIndex));

            FrameProcessingStage stage = new FrameProcessingStage(
                    queues.get(stageIndex),
                    queues.get(stageIndex + 1),
                    stageProcessors,
                    videoInfo);

            Thread worker = new Thread(
                    stage,
                    "FrameStage-" + (stageIndex + 1));

            stages.add(stage);
            workerThreads.add(worker);
        }
    }
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("The pipeline is closed.");
        }

        if (started) {
            return;
        }

        for (Thread worker : workerThreads) {
            worker.start();
        }

        started = true;
    }

    // A successful offer transfers ownership of the task to the pipeline.
    public synchronized boolean offer(FrameTask task) {
        if (!started || closed || getFailure() != null) {
            return false;
        }

        return queues.get(0).offer(task);
    }

    // Return immediately if no completed frame is available.
    public synchronized FrameTask poll() {
        if (!started || closed) {
            return null;
        }

        return queues.get(queues.size() - 1).poll();
    }

    public Exception getFailure() {
        for (FrameProcessingStage stage : stages) {
            Exception failure = stage.getFailure();

            if (failure != null) {
                return failure;
            }
        }

        return null;
    }
    /**
     * Stops workers and releases frames still owned by the pipeline.
     * Call this from the playback worker because waiting may take time.
     */
    public void shutdown() {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
        }

        // Interrupt every worker before waiting for any worker.
        for (Thread worker : workerThreads) {
            worker.interrupt();
        }

        boolean interrupted = false;

        for (Thread worker : workerThreads) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        // Workers have stopped, so queued frames are no longer being modified.
        for (BlockingQueue<FrameTask> queue : queues) {
            FrameTask task = queue.poll();

            while (task != null) {
                task.getFrame().close();
                task = queue.poll();
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
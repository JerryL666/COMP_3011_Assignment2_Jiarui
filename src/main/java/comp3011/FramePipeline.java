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
}
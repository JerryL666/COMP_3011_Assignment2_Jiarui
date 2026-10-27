/*
 * Author: Jiarui Li
 * Developed with assistance from GPT6.1.
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Applies one consecutive group of effects to frames in queue order.
 * Each processor in this stage must be used only by this stage.
 */
public class FrameProcessingStage implements Runnable {
    private final BlockingQueue<FrameTask> inputQueue;
    private final BlockingQueue<FrameTask> outputQueue;
    private final List<FrameProcessor> processors;
    private final InfoVideo videoInfo;

    // The playback coordinator checks this field for worker failures.
    private volatile Exception failure;

    public FrameProcessingStage(
            BlockingQueue<FrameTask> inputQueue,
            BlockingQueue<FrameTask> outputQueue,
            List<FrameProcessor> processors,
            InfoVideo videoInfo) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.processors = new ArrayList<>(processors);
        this.videoInfo = videoInfo;
    }

    @Override
    public void run() {
        FrameTask currentTask = null;

        try {
            for (FrameProcessor processor : processors) {
                processor.initialise(videoInfo);
            }

            while (!Thread.currentThread().isInterrupted()) {
                currentTask = inputQueue.take();

                for (FrameProcessor processor : processors) {
                    processor.process(
                            currentTask.getFrame(),
                            currentTask.getInfo());
                }

                outputQueue.put(currentTask);

                // Ownership passes to the next stage after a successful put.
                currentTask = null;
            }
        } catch (InterruptedException exception) {
            // Interruption is the normal way to stop a waiting worker.
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            failure = exception;
        } finally {
            // Release a frame that could not be passed to the next stage.
            if (currentTask != null) {
                currentTask.getFrame().close();
            }
        }
    }

    public Exception getFailure() {
        return failure;
    }
}
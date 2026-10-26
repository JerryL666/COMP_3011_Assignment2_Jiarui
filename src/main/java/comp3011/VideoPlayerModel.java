/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Jiarui Li(a1904422), developed with assistance from OpenAI Codex.
 *
 * Modifications:
 *   Replaced hard-coded effect selection with a supplied processor list.
 *   Added independent frame copies and explicit copied-frame cleanup.
 *   Added playback identifiers for future stale-frame checks.
 *   Moved playback operations to a single background worker.
 *   Routed view callbacks through the JavaFX application thread.
 *   Connected frame processing to the worker pipeline.
 *   Added pipeline cleanup when playback resources are closed.
 *   Added bounded multi-frame pipeline processing.
 *   Discarded obsolete results after seeking and drained work before EOF cleanup.
 *    
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * Owns decoding, playback timing, audio scheduling and frame processing.
 *
 * Playback control and decoding run on one background worker. Effect stages
 * run on their own threads, with a bounded number of frames in flight.
 * View callbacks are dispatched to the JavaFX application thread.
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int MAX_FRAMES_IN_PIPELINE = 8;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // Decoder access and playback state changes run on this worker.
    private final PlaybackWorker playbackWorker;
    private FramePipeline framePipeline;
    private FrameTask pendingFrameTask;
    private int framesInPipeline;
    private boolean endOfVideo;
    private boolean previewRequested;

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean playbackOpen; // Accessed only by the playback worker.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private boolean audioAvailable;
    private volatile int playbackId;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
 
        this.frameProcessors = new ArrayList<>(frameProcessors);
        playbackWorker = new PlaybackWorker(this::handlePlaybackError);
    }

    // Public controls enqueue work instead of changing playback state on the UI thread.
    public void play(File file) {
        playbackWorker.submit(() -> playOnWorker(file));
    }

    public void startOver() {
        playbackWorker.submit(this::startOverOnWorker);
    }

    public void backFiveSeconds() {
        playbackWorker.submit(this::backFiveSecondsOnWorker);
    }

    public void forwardFiveSeconds() {
        playbackWorker.submit(this::forwardFiveSecondsOnWorker);
    }

    public void togglePause() {
        playbackWorker.submit(this::togglePauseOnWorker);
    }

    public void toggleAudioOutput() {
        playbackWorker.submit(this::toggleAudioOutputOnWorker);
    }

    public void stopPlayback() {
        playbackWorker.submit(this::stopPlaybackOnWorker);
    }

    private void playOnWorker(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    private void startOverOnWorker() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    private void backFiveSecondsOnWorker() {
        seekRelative(-FIVE_SECONDS_US);
    }

    private void forwardFiveSecondsOnWorker() {
        seekRelative(FIVE_SECONDS_US);
    }

    private void togglePauseOnWorker() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        previewRequested = false;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    private void toggleAudioOutputOnWorker() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    private void stopPlaybackOnWorker() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        playbackWorker.shutdown(this::closePlaybackResources);
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackWorker.startUpdates(
                    () -> pumpPlayback(System.nanoTime()));
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        playbackId++;

        // An unsubmitted frame still belongs to the model.
        if (pendingFrameTask != null) {
            pendingFrameTask.getFrame().close();
            pendingFrameTask = null;
        }

        pendingAudio.clear();
        preparedFrame = null;
        endOfVideo = false;
        previewRequested = pauseRequested;

        // Old tasks remain counted until they return and are released.
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() throws Exception {
        if (!playbackOpen
                || preparedFrame != null
                || framePipeline == null
                || (pauseRequested && !previewRequested)) {
            return;
        }

        FrameTask task = framePipeline.poll();

        while (task != null) {
            framesInPipeline--;

            try {
                // Old tasks may finish after the user has sought elsewhere.
                if (task.getPlaybackId() == playbackId) {
                    InfoFrame info = task.getInfo();
                    Image image = converter.convert(task.getFrame());

                    long relativeTimestampUs =
                            Math.max(0, info.timestampUs() - firstTimestampUs);
                    long logicalTimestampUs =
                            logicalPlaybackBaseUs + relativeTimestampUs;
                    long targetTimeNs =
                            playbackStartNs + relativeTimestampUs * 1_000L;

                    preparedFrame = new PreparedFrame(
                            image,
                            info.frameNumber(),
                            info.timestampUs(),
                            logicalTimestampUs,
                            targetTimeNs,
                            System.nanoTime());

                    return;
                }
            } finally {
                // Both accepted results and obsolete results must be released.
                task.getFrame().close();
            }

            task = framePipeline.poll();
        }
    }

    private FrameTask readNextVideoTask() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                endOfVideo = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (framePipeline == null) {
                initialiseFramePipeline(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);

            // Keep the task independent of the decoder's reusable buffer.
            return new FrameTask(frame.clone(), info, playbackId);
        }

        return null;
    }
    
    private void feedPipeline() throws Exception {
        if (endOfVideo || framesInPipeline >= MAX_FRAMES_IN_PIPELINE) {
            return;
        }

        // While paused, decode only the frame needed for a seek preview.
        if (pauseRequested
                && (!previewRequested
                    || framesInPipeline > 0
                    || preparedFrame != null)) {
            return;
        }

        if (pendingFrameTask == null) {
            pendingFrameTask = readNextVideoTask();
        }

        if (pendingFrameTask != null
                && framePipeline.offer(pendingFrameTask)) {
            framesInPipeline++;
            pendingFrameTask = null;
        }
    }

    // Playback updates never wait for a frame to finish processing.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        try {
            if (framePipeline != null) {
                Exception failure = framePipeline.getFailure();

                if (failure != null) {
                    throw failure;
                }
            }

            writeDueAudio(now);
            prepareNextFrame();

            if (preparedFrame != null) {
                boolean frameDue =
                        !pauseRequested && preparedFrame.targetTimeNs() <= now;

                if (previewRequested || frameDue) {
                    displayPreparedFrame(now);
                    previewRequested = false;
                }
            }

            if (!pauseRequested || previewRequested) {
                feedPipeline();
            }

            // Reading EOF does not mean that all queued work has finished.
            if (!pauseRequested
                    && endOfVideo
                    && pendingFrameTask == null
                    && framesInPipeline == 0
                    && preparedFrame == null
                    && pendingAudio.isEmpty()) {
                finishPlayback();
            }
        } catch (Exception exception) {
            handlePlaybackError(exception);
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        // Preserve the final image callback when playback ends naturally.
        closePlaybackResources(false);
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        closePlaybackResources(true);
    }

    private void closePlaybackResources(boolean invalidateImages) {
        playbackWorker.stopUpdates();

        // Invalidate old images for explicit stops, but preserve the final EOF image.
        if (invalidateImages) {
            playbackId++;
        }
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;

        // Stop workers before reusing processors or releasing playback resources.
        if (framePipeline != null) {
            framePipeline.shutdown();
            framePipeline = null;
        }

        if (pendingFrameTask != null) {
            pendingFrameTask.getFrame().close();
            pendingFrameTask = null;
        }

        framesInPipeline = 0;
        endOfVideo = false;
        previewRequested = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFramePipeline(InfoVideo videoInfo) {
        framePipeline = new FramePipeline(frameProcessors, videoInfo);
        framePipeline.start();
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        Platform.runLater(
                () -> videoSizeChangedHandler.accept(width, height));
    }

    private void notifyFrameReady(Image image) {
        int imagePlaybackId = playbackId;

        Platform.runLater(() -> {
            // Ignore images queued before a seek or playback reset.
            if (imagePlaybackId == playbackId) {
                frameReadyHandler.accept(image);
            }
        });
    }

    private void notifyStatusChanged(String status) {
        Platform.runLater(
                () -> statusChangedHandler.accept(status));
    }

    private void notifyPlaybackStateChanged() {
        boolean playing = playbackOpen;
        boolean paused = pauseRequested;

        Platform.runLater(
                () -> playbackStateChangedHandler.accept(playing, paused));
    }

    private void notifyAudioOutputStateChanged() {
        boolean enabled = audioOutputEnabled;

        Platform.runLater(
                () -> audioOutputStateChangedHandler.accept(enabled));
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}

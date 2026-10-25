/*
 * Author: Jiarui Li
 * Developed with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;

/**
 * Carries one video frame and its information through the processing pipeline.
 * Only the stage currently holding this task may modify its frame.
 */
public class FrameTask {
    private final Frame frame;
    private final InfoFrame info;
    private final int playbackId;

    public FrameTask(Frame frame, InfoFrame info, int playbackId) {
        this.frame = frame;
        this.info = info;
        this.playbackId = playbackId;
    }

    public Frame getFrame() {
        return frame;
    }

    public InfoFrame getInfo() {
        return info;
    }

    public int getPlaybackId() {
        return playbackId;
    }
}
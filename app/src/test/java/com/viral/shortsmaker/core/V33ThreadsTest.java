package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** code 33 (5.1): one-at-a-time renders cap x264 at 6 threads (8-core phone: 520 -> 384 MB peak, same speed on the box) */
public class V33ThreadsTest {
    @Test public void capsX264ThreadsOnManyCores() {
        assertEquals(6, Pipeline.sequentialX264Threads(8));
        assertEquals(6, Pipeline.sequentialX264Threads(6));
        assertEquals(0, Pipeline.sequentialX264Threads(4));   // auto on 4 cores is 6 already
        String[] cmd = FfmpegCommands.renderClip("/v.mp4", "/a.webm", 0, 20, FfmpegCommands.Frame.FILL, null, null, "/o.mp4", 1080,
                FfmpegCommands.Encoder.X264, Pipeline.sequentialX264Threads(8), null, false, null, 1080);
        java.util.List<String> l = java.util.Arrays.asList(cmd);
        assertEquals("6", l.get(l.lastIndexOf("-threads") + 1));
    }
}

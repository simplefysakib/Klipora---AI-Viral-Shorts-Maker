package com.viral.shortsmaker.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.Word;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * code 33 (5.1): the backup video engine. The same edit as the FFmpeg render - same cut, 30 fps, same framing (fit /
 * blurred background / fill crop / face-tracking camera + split-screen panels), same captions, the audio with its
 * 80 ms fade-in as 48 kHz stereo AAC - made only with the phone's own parts: MediaExtractor -> MediaCodec decoder ->
 * GPU (OpenGL ES 2) -> MediaCodec hardware encoder -> MediaMuxer. No FFmpeg library is loaded, so a phone whose FFmpeg
 * crashes (his vivo Y16, signal 11 in libswscale / libavfilter) can still make shorts. Captions are drawn by the same
 * painter as the Caption Studio preview (same fit, pages, colours, outline, box, pop / karaoke animation).
 * Not done here (FFmpeg path only): the light denoise before the enlarge.
 */
public final class NativeEngine {
    private NativeEngine() {}

    public interface Progress { void on(double sec); }
    public interface Cancel { boolean cancelled(); }

    // ---------------------------------------------------------------------------------------------- spec (pure Java)

    public static JSONObject spec(String video, String audio, double ss, double dur, String out, int outW, FfmpegCommands.Frame frame,
                                  double aspect, long bitrate, String codecName) throws Exception {
        int[] d = FfmpegCommands.dims(outW);
        JSONObject o = new JSONObject();
        o.put("video", video).put("audio", audio == null ? "" : audio).put("ss", ss).put("dur", dur).put("out", out)
                .put("w", d[0]).put("h", d[1]).put("frame", (frame == null ? FfmpegCommands.Frame.BLUR : frame).name())
                .put("aspect", aspect).put("bitrate", bitrate).put("codec", codecName == null ? "" : codecName);
        return o;
    }

    /** one line per output frame: "c lx" (camera window, x position 0..1) or "s pw ph tx ty bx by" (split-screen panels) */
    public static String trackLines(FaceTrack.Plan p, int frames) {
        StringBuilder sb = new StringBuilder(frames * 12);
        List<int[]> segs = p.segments();
        for (int k = 0; k < frames; k++) {
            int[] seg = segs.get(segs.size() - 1);
            for (int[] g : segs) if (k >= g[1] && k < g[2]) { seg = g; break; }
            if (seg[0] == 1 && seg[3] < p.splits.size()) {
                FaceTrack.Split s = p.splits.get(seg[3]);
                sb.append(String.format(Locale.US, "s %.5f %.5f %.5f %.5f %.5f %.5f\n", s.pw, s.ph,
                        FaceTrack.lxOf(s.topCx, s.pw), FaceTrack.lyOf(s.topCy, s.ph), FaceTrack.lxOf(s.botCx, s.pw), FaceTrack.lyOf(s.botCy, s.ph)));
            } else sb.append(String.format(Locale.US, "c %.5f\n", p.lx(k)));
        }
        return sb.toString();
    }

    public static JSONArray wordsJson(List<Word> words) throws Exception {
        JSONArray a = new JSONArray();
        for (Word w : words) a.put(new JSONArray().put(w.text).put(w.start).put(w.end));
        return a;
    }

    static List<Word> wordsFrom(JSONArray a) {
        List<Word> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONArray w = a.optJSONArray(i);
            if (w != null) l.add(new Word(w.optString(0), w.optDouble(1), w.optDouble(2)));
        }
        return l;
    }

    /** per-frame crop of the face-tracking plan, parsed: {kind(0 camera / 1 split), v1..v6} */
    static float[][] parseTrack(String text) {
        List<float[]> l = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] t = line.trim().split(" ");
            if (t.length >= 2 && "c".equals(t[0])) l.add(new float[]{0, Float.parseFloat(t[1])});
            else if (t.length >= 7 && "s".equals(t[0])) {
                float[] f = new float[7];
                f[0] = 1;
                for (int i = 1; i < 7; i++) f[i] = Float.parseFloat(t[i]);
                l.add(f);
            }
        }
        return l.toArray(new float[0][]);
    }

    /**
     * the source rectangle (pixels, x y w h, top-left origin) of one framing, the same arithmetic as the FFmpeg graphs:
     * FILL = crop w=min(iw,trunc(ih*r/2)*2) h=min(ih,trunc(iw/r/2)*2) centred; camera = that width, full height, x by lx.
     */
    static float[] fillRect(int iw, int ih, double r) {
        float w = (float) Math.min(iw, Math.floor(ih * r / 2) * 2), h = (float) Math.min(ih, Math.floor(iw / r / 2) * 2);
        return new float[]{(iw - w) / 2f, (ih - h) / 2f, w, h};
    }

    static float[] camRect(int iw, int ih, double r, float lx) {
        float w = (float) Math.min(iw, Math.floor(ih * r / 2) * 2), h = (float) (Math.floor(ih / 2.0) * 2);
        return new float[]{(iw - w) * lx, 0, w, h};
    }

    static float[] panelRect(int iw, int ih, float pw, float ph, float lx, float ly) {
        float w = (float) (Math.floor(iw * pw / 2) * 2), h = (float) (Math.floor(ih * ph / 2) * 2);
        return new float[]{(iw - w) * lx, (ih - h) * ly, w, h};
    }

    /** fit-inside rectangle of the source in the output (x y w h, top-left origin) */
    static float[] fitRect(int iw, int ih, int ow, int oh) {
        double s = Math.min((double) ow / iw, (double) oh / ih);
        float w = (float) (iw * s), h = (float) (ih * s);
        return new float[]{(ow - w) / 2f, (oh - h) / 2f, w, h};
    }

    /** light luma sharpening like the FFmpeg path: only when the crop is enlarged (amount by how much) */
    static float sharpAmount(float cropH, int outH) {
        if (cropH <= 0 || cropH >= outH * 0.98f) return 0f;
        return cropH <= outH * 0.6f ? 0.55f : 0.35f;
    }

    // ---------------------------------------------------------------------------------------------- run

    /** runs a backup-engine "command"; null = done, else the error in a few words */
    public static String runArgs(Context c, String[] args, Progress p, Cancel cancel) {
        try {
            if (args == null || args.length < 2 || !RenderSafety.NATIVE_ARG.equals(args[0])) return "not a backup engine command";
            JSONObject o = new JSONObject(args[1]);
            new Job(c, o, p, cancel).run();
            return null;
        } catch (Throwable t) {
            String m = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            try { AppLog.debug("Backup engine failed: " + android.util.Log.getStackTraceString(t)); } catch (Throwable ignored) {}
            return m.length() > 300 ? m.substring(0, 300) : m;
        }
    }

    private static final class Sample {
        final ByteBuffer data; final long pts; final int flags;
        Sample(ByteBuffer d, long p, int f) { data = d; pts = p; flags = f; }
    }

    private static final class Job {
        final Context ctx; final JSONObject o; final Progress prog; final Cancel cancel;
        final int W, H, frames; final long startUs, durUs; final double aspect;
        final FfmpegCommands.Frame frame;
        float[][] track;
        int srcH;
        MediaFormat audioFormat;
        final ArrayDeque<Sample> audio = new ArrayDeque<>();

        Job(Context c, JSONObject o, Progress p, Cancel cn) throws Exception {
            ctx = c; this.o = o; prog = p; cancel = cn;
            W = o.getInt("w"); H = o.getInt("h");
            double dur = o.getDouble("dur");
            frames = Math.max(1, (int) Math.round(dur * FfmpegCommands.OUTPUT_FPS));
            startUs = Math.round(Math.max(0, o.getDouble("ss")) * 1e6);
            durUs = Math.round(dur * 1e6);
            aspect = o.optDouble("aspect", (double) W / H);
            frame = FfmpegCommands.Frame.valueOf(o.optString("frame", "BLUR"));
            String tp = o.optString("track", "");
            if (!tp.isEmpty()) track = parseTrack(readText(new File(tp)));
            srcH = o.optInt("trackSrcH", o.optInt("srcH", 0));
        }

        void check() throws Exception { if (cancel != null && cancel.cancelled()) throw new InterruptedException("cancelled"); }

        void run() throws Exception {
            String audioPath = o.optString("audio", "");
            encodeAudio(audioPath.isEmpty() ? o.getString("video") : audioPath);
            check();
            renderVideo();
        }

        // ------------------------------------------------------------------------------------------ audio

        void encodeAudio(String path) throws Exception {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null, enc = null;
            try {
                ex.setDataSource(path);
                int ti = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { ti = i; break; }
                }
                if (ti < 0) { AppLog.debug("Backup engine: no audio track in " + path); return; }
                MediaFormat inF = ex.getTrackFormat(ti);
                ex.selectTrack(ti);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME));
                dec.configure(inF, null, null, 0);
                dec.start();
                int sr = inF.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? inF.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                int ch = inF.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? inF.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                // decode the cut into 16-bit PCM at the source rate, then 48 kHz stereo
                java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
                long need = -1;   // source frames needed
                long skip = -1;
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inDone = false, outDone = false;
                long endUs = startUs + durUs;
                while (!outDone) {
                    check();
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer b = dec.getInputBuffer(ii);
                            int n = ex.readSampleData(b, 0);
                            if (n < 0 || ex.getSampleTime() > endUs + 300_000) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int oi = dec.dequeueOutputBuffer(bi, 10000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = dec.getOutputFormat();
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else if (oi >= 0) {
                        if (bi.size > 0) {
                            ByteBuffer b = dec.getOutputBuffer(oi);
                            b.position(bi.offset); b.limit(bi.offset + bi.size);
                            byte[] chunk = new byte[bi.size];
                            b.get(chunk);
                            int fb = 2 * Math.max(1, ch);
                            if (skip < 0) {
                                skip = Math.max(0, Math.round((startUs - bi.presentationTimeUs) / 1e6 * sr)) * fb;
                                need = Math.round(durUs / 1e6 * sr) * fb;
                            }
                            int from = 0;
                            if (skip > 0) { int s = (int) Math.min(skip, chunk.length); skip -= s; from = s; }
                            int take = (int) Math.min(chunk.length - from, Math.max(0, need - pcm.size()));
                            if (take > 0) pcm.write(chunk, from, take);
                            if (need >= 0 && pcm.size() >= need) outDone = true;
                        }
                        dec.releaseOutputBuffer(oi, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    }
                }
                short[] st = toStereo48k(pcm.toByteArray(), sr, ch, (int) Math.round(durUs / 1e6 * 48000));
                fadeIn(st, 48000, 0.08);
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
                ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                ef.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
                ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
                int pos = 0;   // in shorts
                boolean eos = false, done = false;
                while (!done) {
                    check();
                    if (!eos) {
                        int ii = enc.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer b = enc.getInputBuffer(ii);
                            b.clear();
                            int n = Math.min(b.capacity() / 2, Math.min(2048, st.length - pos));
                            long pts = (long) ((pos / 2) * 1e6 / 48000);
                            if (n <= 0) { enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true; }
                            else {
                                b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(st, pos, n);
                                enc.queueInputBuffer(ii, 0, n * 2, pts, 0);
                                pos += n;
                            }
                        }
                    }
                    int oi = enc.dequeueOutputBuffer(bi, 10000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) audioFormat = enc.getOutputFormat();
                    else if (oi >= 0) {
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                            ByteBuffer b = enc.getOutputBuffer(oi);
                            b.position(bi.offset); b.limit(bi.offset + bi.size);
                            ByteBuffer copy = ByteBuffer.allocate(bi.size);
                            copy.put(b); copy.flip();
                            audio.add(new Sample(copy, bi.presentationTimeUs, bi.flags));
                        }
                        enc.releaseOutputBuffer(oi, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    }
                }
            } finally {
                try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) {}
                try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ignored) {}
                ex.release();
            }
        }

        // ------------------------------------------------------------------------------------------ video

        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY; EGLContext eglCtx = EGL14.EGL_NO_CONTEXT; EGLSurface eglSurf = EGL14.EGL_NO_SURFACE;
        int progOes, progTex, oesTex, capTex = 0;
        int[] fbo = new int[2], fboTex = new int[2];
        int bw, bh;
        int iw, ih;
        final float[] stM = new float[16];
        FloatBuffer quad;
        Bitmap capBmp; Canvas capCanvas; CaptionPreviewView painter;
        final Object frameLock = new Object();
        boolean frameReady;

        void renderVideo() throws Exception {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null, enc = null;
            MediaMuxer mux = null;
            SurfaceTexture st = null; Surface decSurface = null, encSurface = null;
            HandlerThread ht = new HandlerThread("native-frames");
            File out = new File(o.getString("out"));
            boolean muxStarted = false;
            try {
                ex.setDataSource(o.getString("video"));
                int ti = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) { ti = i; break; }
                }
                if (ti < 0) throw new Exception("no video track");
                MediaFormat inF = ex.getTrackFormat(ti);
                int rot = inF.containsKey("rotation-degrees") ? inF.getInteger("rotation-degrees") : 0;
                iw = inF.getInteger(MediaFormat.KEY_WIDTH); ih = inF.getInteger(MediaFormat.KEY_HEIGHT);
                if (rot % 180 != 0) { int t = iw; iw = ih; ih = t; }
                if (srcH <= 0) srcH = ih;
                ex.selectTrack(ti);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                // encoder + EGL on its input surface
                MediaFormat ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
                ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                ef.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.max(1_000_000, Math.min(60_000_000, o.optLong("bitrate", 8_000_000))));
                ef.setInteger(MediaFormat.KEY_FRAME_RATE, FfmpegCommands.OUTPUT_FPS);
                ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                String cn = o.optString("codec", "");
                enc = null;
                if (!cn.isEmpty()) { try { enc = MediaCodec.createByCodecName(cn); enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); } catch (Throwable t) { if (enc != null) enc.release(); enc = null; } }
                if (enc == null) { enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
                encSurface = enc.createInputSurface();
                enc.start();
                setupEgl(encSurface);
                setupGl();
                setupCaptions();

                ht.start();
                st = new SurfaceTexture(oesTex);
                st.setOnFrameAvailableListener(s -> { synchronized (frameLock) { frameReady = true; frameLock.notifyAll(); } }, new Handler(ht.getLooper()));
                decSurface = new Surface(st);
                dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME));
                dec.configure(inF, decSurface, null, 0);
                dec.start();

                if (out.exists()) out.delete();
                mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int[] tracks = {-1, -1};   // video, audio
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(), ebi = new MediaCodec.BufferInfo();
                boolean inDone = false, decDone = false, haveFrame = false;
                int k = 0;   // next output frame
                long lastProgress = 0;
                long idleSince = System.currentTimeMillis();
                long endUs = startUs + durUs;
                while (k < frames) {
                    check();
                    if (!inDone) {
                        int ii = dec.dequeueInputBuffer(5000);
                        if (ii >= 0) {
                            ByteBuffer b = dec.getInputBuffer(ii);
                            int n = ex.readSampleData(b, 0);
                            if (n < 0 || ex.getSampleTime() > endUs + 1_000_000) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    if (decDone) {
                        // the source ended early: hold its last picture to the end of the short
                        if (!haveFrame) throw new Exception("the video decoded no picture");
                        while (k < frames) { drawAndEncode(k, enc, mux, tracks, ebi); k++; }
                        break;
                    }
                    int oi = dec.dequeueOutputBuffer(bi, 5000);
                    if (oi >= 0) {
                        idleSince = System.currentTimeMillis();
                        boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        double t = (bi.presentationTimeUs - startUs) / 1e6;
                        if (bi.size > 0 && t >= -0.5 / FfmpegCommands.OUTPUT_FPS) {
                            // frames shown before this picture keep the previous one (sample and hold, like fps=30)
                            if (haveFrame) while (k < frames && (k + 0.5) / FfmpegCommands.OUTPUT_FPS <= t) { drawAndEncode(k, enc, mux, tracks, ebi); k++; }
                            synchronized (frameLock) { frameReady = false; }
                            dec.releaseOutputBuffer(oi, true);
                            synchronized (frameLock) {
                                long until = System.currentTimeMillis() + 2500;
                                while (!frameReady && System.currentTimeMillis() < until) frameLock.wait(100);
                                if (!frameReady) throw new Exception("the decoder stopped delivering pictures");
                            }
                            st.updateTexImage();
                            st.getTransformMatrix(stM);
                            if (!haveFrame) {
                                haveFrame = true;
                                while (k < frames && (k + 0.5) / FfmpegCommands.OUTPUT_FPS <= t) { drawAndEncode(k, enc, mux, tracks, ebi); k++; }
                            }
                        } else dec.releaseOutputBuffer(oi, false);
                        if (eos) decDone = true;
                    } else if (System.currentTimeMillis() - idleSince > 15000) throw new Exception("the decoder hung");
                    if (prog != null && k - lastProgress >= 15) { lastProgress = k; prog.on(k / (double) FfmpegCommands.OUTPUT_FPS); }
                }
                enc.signalEndOfInputStream();
                drainEncoder(enc, mux, tracks, ebi, true);
                if (!muxStartedOk) throw new Exception("the encoder produced no video");
                writeAudio(mux, tracks, Long.MAX_VALUE);
                muxStarted = true;
            } finally {
                try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) {}
                try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ignored) {}
                try { if (mux != null) { if (muxStartedOk) mux.stop(); mux.release(); } } catch (Throwable t) { if (muxStarted) throw new Exception("muxer: " + t.getMessage()); }
                try { if (st != null) st.release(); } catch (Throwable ignored) {}
                try { if (decSurface != null) decSurface.release(); } catch (Throwable ignored) {}
                releaseEgl();
                try { if (encSurface != null) encSurface.release(); } catch (Throwable ignored) {}
                ht.quitSafely();
                ex.release();
                if (capBmp != null) capBmp.recycle();
            }
            if (!out.exists() || out.length() < 1000) throw new Exception("no output file");
        }

        boolean muxStartedOk;

        void drawAndEncode(int k, MediaCodec enc, MediaMuxer mux, int[] tracks, MediaCodec.BufferInfo ebi) throws Exception {
            drawFrame(k);
            EGLExt.eglPresentationTimeANDROID(dpy, eglSurf, (long) (k * 1e9 / FfmpegCommands.OUTPUT_FPS));
            if (!EGL14.eglSwapBuffers(dpy, eglSurf)) throw new Exception("eglSwapBuffers " + EGL14.eglGetError());
            drainEncoder(enc, mux, tracks, ebi, false);
        }

        void drainEncoder(MediaCodec enc, MediaMuxer mux, int[] tracks, MediaCodec.BufferInfo bi, boolean end) throws Exception {
            long until = System.currentTimeMillis() + (end ? 10000 : 0);
            while (true) {
                int oi = enc.dequeueOutputBuffer(bi, end ? 10000 : 0);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || System.currentTimeMillis() > until) return; continue; }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStartedOk) throw new Exception("encoder format changed twice");
                    tracks[0] = mux.addTrack(enc.getOutputFormat());
                    if (audioFormat != null && !audio.isEmpty()) tracks[1] = mux.addTrack(audioFormat);
                    mux.start();
                    muxStartedOk = true;
                    continue;
                }
                if (oi < 0) continue;
                ByteBuffer b = enc.getOutputBuffer(oi);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                if (bi.size > 0 && muxStartedOk) {
                    b.position(bi.offset); b.limit(bi.offset + bi.size);
                    mux.writeSampleData(tracks[0], b, bi);
                    writeAudio(mux, tracks, bi.presentationTimeUs);
                }
                enc.releaseOutputBuffer(oi, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        void writeAudio(MediaMuxer mux, int[] tracks, long uptoUs) {
            if (tracks[1] < 0 || !muxStartedOk) return;
            MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
            while (!audio.isEmpty() && audio.peekFirst().pts <= uptoUs) {
                Sample s = audio.pollFirst();
                ai.set(0, s.data.remaining(), s.pts, s.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                if (ai.size > 0) mux.writeSampleData(tracks[1], s.data, ai);
            }
        }

        // ------------------------------------------------------------------------------------------ GL

        void setupEgl(Surface surface) throws Exception {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new Exception("eglInitialize");
            int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) || n[0] < 1) throw new Exception("eglChooseConfig");
            eglCtx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (eglCtx == null || eglCtx == EGL14.EGL_NO_CONTEXT) throw new Exception("eglCreateContext");
            eglSurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurf == null || eglSurf == EGL14.EGL_NO_SURFACE) throw new Exception("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(dpy, eglSurf, eglSurf, eglCtx)) throw new Exception("eglMakeCurrent");
        }

        void releaseEgl() {
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurf);
                    if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, eglCtx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {}
            dpy = EGL14.EGL_NO_DISPLAY;
        }

        static final String VS = "attribute vec2 aPos; attribute vec2 aUv; uniform mat4 uTex; uniform vec4 uCrop; varying vec2 vUv;\n"
                + "void main(){ gl_Position = vec4(aPos, 0.0, 1.0);\n"
                + " vec2 s = vec2(uCrop.x + aUv.x * uCrop.z, 1.0 - (uCrop.y + aUv.y * uCrop.w));\n"
                + " vUv = (uTex * vec4(s, 0.0, 1.0)).xy; }";
        static final String FS_OES = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n"
                + "uniform samplerExternalOES sTex; uniform vec2 uTexel; uniform float uSharp; uniform float uBright; varying vec2 vUv;\n"
                + "void main(){ vec4 c = texture2D(sTex, vUv);\n"
                + " if (uSharp > 0.0) { vec3 b = (texture2D(sTex, vUv + vec2(uTexel.x, 0.0)).rgb + texture2D(sTex, vUv - vec2(uTexel.x, 0.0)).rgb\n"
                + "   + texture2D(sTex, vUv + vec2(0.0, uTexel.y)).rgb + texture2D(sTex, vUv - vec2(0.0, uTexel.y)).rgb) * 0.25;\n"
                + "   float l = dot(c.rgb - b, vec3(0.299, 0.587, 0.114)); c.rgb = clamp(c.rgb + uSharp * l, 0.0, 1.0); }\n"
                + " gl_FragColor = vec4(clamp(c.rgb + uBright, 0.0, 1.0), 1.0); }";
        static final String FS_TEX = "precision mediump float; uniform sampler2D sTex; uniform vec2 uStep; uniform float uBright; varying vec2 vUv;\n"
                + "void main(){ vec4 c;\n"
                + " if (uStep.x == 0.0 && uStep.y == 0.0) c = texture2D(sTex, vUv);\n"
                + " else { c = vec4(0.0); for (int i = -6; i <= 6; i++) c += texture2D(sTex, vUv + uStep * float(i)); c /= 13.0; }\n"
                + " gl_FragColor = vec4(clamp(c.rgb + uBright, 0.0, 1.0), c.a); }";

        static int shader(int type, String src) throws Exception {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new Exception("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        static int program(String fs) throws Exception {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VS));
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glBindAttribLocation(p, 1, "aUv");
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new Exception("program: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        void setupGl() throws Exception {
            progOes = program(FS_OES);
            progTex = program(FS_TEX);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // pos(x,y) uv(u,v); v = 0 at the top of the picture
            float[] q = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
            if (frame == FfmpegCommands.Frame.BLUR && track == null) {
                // small blurred background like the FFmpeg graph (144x256 for 9:16)
                double a = (double) W / H;
                bw = a < 0.8 ? 144 : a > 1.2 ? 256 : 192; bh = a < 0.8 ? 256 : a > 1.2 ? 144 : 192;
                GLES20.glGenFramebuffers(2, fbo, 0);
                GLES20.glGenTextures(2, fboTex, 0);
                for (int i = 0; i < 2; i++) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]);
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bw, bh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                    texParams2D();
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0);
                    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new Exception("framebuffer");
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
        }

        static void texParams2D() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        static final float[] IDENT = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        /** 2D textures (our own FBOs / the caption bitmap) are stored top row first: flip v */
        static final float[] FLIP = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};

        void bindQuad(int prog) {
            quad.position(0);
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(0);
            quad.position(2);
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(1);
        }

        /** draws the source rect (pixels, top-left origin) into the viewport (pixels, top-left origin of a canvas tw x th) */
        void drawSource(float[] src, float vx, float vy, float vw, float vh, int th, float sharp, float bright) {
            GLES20.glViewport(Math.round(vx), Math.round(th - vy - vh), Math.round(vw), Math.round(vh));
            GLES20.glUseProgram(progOes);
            bindQuad(progOes);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "sTex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uTex"), 1, false, stM, 0);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(progOes, "uCrop"), src[0] / iw, src[1] / ih, src[2] / iw, src[3] / ih);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progOes, "uTexel"), 1f / iw, 1f / ih);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uSharp"), sharp);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uBright"), bright);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void drawTex(int tex, int vx, int vy, int vw, int vh, float sx, float sy, float bright, boolean blend) {
            GLES20.glViewport(vx, vy, vw, vh);
            GLES20.glUseProgram(progTex);
            bindQuad(progTex);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "sTex"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progTex, "uTex"), 1, false, FLIP, 0);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(progTex, "uCrop"), 0, 0, 1, 1);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progTex, "uStep"), sx, sy);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progTex, "uBright"), bright);
            if (blend) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (blend) GLES20.glDisable(GLES20.GL_BLEND);
        }

        void drawFrame(int k) throws Exception {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, W, H);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (track != null && track.length > 0) {
                float[] f = track[Math.min(k, track.length - 1)];
                if (f[0] == 0) {
                    float[] r = camRect(iw, ih, aspect, f[1]);
                    drawSource(r, 0, 0, W, H, H, sharpAmount(r[3], H), 0);
                } else {
                    float[] top = panelRect(iw, ih, f[1], f[2], f[3], f[4]), bot = panelRect(iw, ih, f[1], f[2], f[5], f[6]);
                    drawSource(top, 0, 0, W, H / 2f, H, sharpAmount(top[3], H / 2), 0);
                    drawSource(bot, 0, H / 2f, W, H / 2f, H, sharpAmount(bot[3], H / 2), 0);
                }
            } else if (frame == FfmpegCommands.Frame.FILL) {
                float[] r = fillRect(iw, ih, aspect);
                drawSource(r, 0, 0, W, H, H, sharpAmount(r[3], H), 0);
            } else {
                float[] full = {0, 0, iw, ih};
                float[] fit = fitRect(iw, ih, W, H);
                if (frame == FfmpegCommands.Frame.BLUR) {
                    // cover crop into the small buffer, blur it there (two passes each way), darken, enlarge
                    float[] cover = fillRect(iw, ih, (double) bw / bh);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                    drawSource(cover, 0, 0, bw, bh, bh, 0, 0);
                    for (int pass = 0; pass < 2; pass++) {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1]);
                        drawTex(fboTex[0], 0, 0, bw, bh, 1f / bw, 0, 0, false);
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
                        drawTex(fboTex[1], 0, 0, bw, bh, 0, 1f / bh, 0, false);
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    drawTex(fboTex[0], 0, 0, W, H, 0, 0, -0.06f, false);
                }
                drawSource(full, fit[0], fit[1], fit[2], fit[3], H, 0, 0);
            }
            drawCaptions(k);
            int e = GLES20.glGetError();
            if (e != GLES20.GL_NO_ERROR) throw new Exception("GL error " + e);
        }

        // ------------------------------------------------------------------------------------------ captions

        void setupCaptions() throws Exception {
            JSONArray words = o.optJSONArray("words");
            if (words == null || words.length() == 0 || o.optJSONObject("style") == null) return;
            final List<Word> ws = wordsFrom(words);
            final CaptionStyle style = CaptionStyle.fromJson(o.getJSONObject("style"));
            final CaptionLayout lay = o.optJSONObject("layout") == null ? new CaptionLayout() : CaptionLayout.fromJson(o.getJSONObject("layout"));
            final double dur = o.getDouble("dur");
            // the view is made on a thread with a Looper (its touch helpers need one); drawing happens here
            final AtomicReference<Object> made = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            Runnable mk = () -> {
                try { CaptionPreviewView v = new CaptionPreviewView(ctx); v.prepareOffscreen(ws, style, lay, dur); made.set(v); }
                catch (Throwable t) { made.set(t); }
                done.countDown();
            };
            if (Looper.myLooper() != null) mk.run(); else new Handler(Looper.getMainLooper()).post(mk);
            if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("caption painter did not start");
            if (made.get() instanceof Throwable) throw new Exception("caption painter: " + made.get());
            painter = (CaptionPreviewView) made.get();
            capBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            capCanvas = new Canvas(capBmp);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            capTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, capTex);
            texParams2D();
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, capBmp, 0);
        }

        int lastSig = Integer.MIN_VALUE;
        boolean lastHad;

        void drawCaptions(int k) {
            if (painter == null) return;
            double t = k / (double) FfmpegCommands.OUTPUT_FPS;
            int sig = painter.captionSignature(t);
            if (sig != lastSig) {
                // redraw + upload only when the picture of the captions changes (page, active word, animation step)
                capBmp.eraseColor(0);
                lastHad = painter.drawCaptionsAt(capCanvas, W, H, t);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, capTex);
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, capBmp);
                lastSig = sig;
            }
            if (lastHad) drawTex(capTex, 0, 0, W, H, 0, 0, 0, true);
        }
    }

    static String readText(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    // ---------------------------------------------------------------------------------------------- audio helpers (pure)

    /** 16-bit PCM (interleaved, ch channels, rate sr) -> 48 kHz stereo, exactly n frames (silence-padded) */
    static short[] toStereo48k(byte[] pcm, int sr, int ch, int n) {
        ch = Math.max(1, ch);
        int frames = pcm.length / (2 * ch);
        short[] out = new short[Math.max(0, n) * 2];
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            double src = i * (double) sr / 48000.0;
            int a = (int) Math.floor(src);
            if (a >= frames) break;
            int c = Math.min(frames - 1, a + 1);
            double f = src - a;
            for (int o = 0; o < 2; o++) {
                int chan = ch == 1 ? 0 : o;
                double va = b.getShort((a * ch + chan) * 2), vc = b.getShort((c * ch + chan) * 2);
                out[i * 2 + o] = (short) Math.round(va + (vc - va) * f);
            }
        }
        return out;
    }

    static void fadeIn(short[] st, int sr, double sec) {
        int n = (int) Math.min(st.length / 2, Math.round(sr * sec));
        for (int i = 0; i < n; i++) {
            double g = i / (double) n;
            st[i * 2] = (short) Math.round(st[i * 2] * g);
            st[i * 2 + 1] = (short) Math.round(st[i * 2 + 1] * g);
        }
    }
}

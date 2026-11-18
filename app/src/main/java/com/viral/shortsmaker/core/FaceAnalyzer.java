package com.viral.shortsmaker.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * v13: on-device face analysis for AI face tracking. Everything is offline: the two MediaPipe models ship in the APK
 * (assets/ft/). The clip is sampled at 5 frames per second, 640 px wide (ffmpeg writes small JPEGs, one at a time
 * in memory here), then for each frame:
 *  - face landmarker (up to 4 faces): face box + inner-lip gap (mouth openness) for the speaker choice;
 *  - wide shots where no face is large: a pose landmarker every other frame finds people the face model misses
 *    (profile views, small faces); their head box is added without a lip value;
 *  - a 32x18 luma thumbnail for shot-cut detection; audio loudness from an 8 kHz mono track.
 * v14: models load once (self-check at start-up), frames are extracted outside the lock; model calls are serialised.
 */
public final class FaceAnalyzer {
    /**
     * v21 (4.0): 8 frames a second (was 5). Lip movement is 4-6 syllables a second, so 5 fps under-sampled it. Box test,
     * Norm Macdonald 3-person wide shot (desktop twin of this analyser, speaker labelled by a 25 fps lip+voice
     * reference): the camera was on the person actually talking 63% -> 86% of the time.
     */
    public static final int FPS = 8;
    public static final int W = 640;
    /** v21: "every 3rd frame at 5 fps" kept as time (~0.6 s) at 8 fps */
    static final int STRIDE = Math.max(3, Math.round(FPS * 0.6f));
    private static final Object LOCK = new Object();

    public static final class Result {
        public List<FaceTrack.Sample> samples = new ArrayList<>();
        public int srcW, srcH;
        public double seconds;
        public int faceCalls, poseCalls;   // v16: model calls made (the log shows them)
        public int tileCalls;              // v18: extra face-model calls on the halves of wide shots
        public int reusedHeads;            // v22: frames that reused the body model's heads of the frame before
        public int engines = 1;            // v22: face engines that worked on this short at once
    }

    public interface Tick { void on(double fraction); }

    private FaceAnalyzer() {}

    // ------------------------------------------------------------------ v14 face engine (load once, self-check)
    /** 0 = not tried yet, 1 = ready, -1 = failed (reason in engineError) */
    private static volatile int engineState = 0;
    private static volatile String engineError = null;
    private static volatile long engineMs = 0;
    private static FaceLandmarker cachedFace;      // guarded by LOCK
    private static PoseLandmarker cachedPose;      // guarded by LOCK

    /** true when the models are in the APK and the engine has not already failed on this phone */
    public static boolean available(Context c) {
        if (engineState < 0) return false;
        try {
            String[] a = c.getAssets().list("ft");
            return a != null && Arrays.asList(a).contains("face_landmarker.task");
        } catch (Throwable t) { return false; }
    }

    public static boolean engineReady() { return engineState > 0; }
    public static String engineError() { return engineError; }

    /** v14: "ExceptionInInitializerError null" told us nothing; this walks the whole cause chain */
    public static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        while (t != null && seen.add(t) && seen.size() <= 5) {
            if (sb.length() > 0) sb.append(" <- ");
            String m = t.getMessage();
            sb.append(t.getClass().getSimpleName()).append(m == null ? "" : ": " + (m.length() > 160 ? m.substring(0, 160) + "…" : m));
            t = t.getCause();
        }
        return sb.toString();
    }

    /**
     * v14 startup self-check (background thread): loads the MediaPipe classes + native library and builds the face
     * model once, then logs one clear line. A phone where it fails never spends time extracting frames for tracking.
     */
    public static void selfCheck(Context ctx) {
        final Context app = ctx.getApplicationContext();
        // code 33 (5.1): the check loaded MediaPipe (+ a GPU inference) 3 s after EVERY app start: 2-3 s of CPU and
        // ~100 MB on a 2-3 GB phone right when the user starts a job. Once it passed on this phone with this build (same
        // Android build, same GPU verdict), it is not repeated at start; a job still builds the engine itself.
        final String key = selfCheckKey(app);
        final android.content.SharedPreferences sp = app.getSharedPreferences("face_gpu", Context.MODE_PRIVATE);
        if (key.equals(sp.getString("ok_key", ""))) {
            AppLog.i("Face engine: passed its check earlier on this phone (" + sp.getString("ok_note", "") + ")");
            return;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}   // after the first screen is up
            try { ensureEngine(app); AppLog.i(statusLine()); sp.edit().putString("ok_key", selfCheckKey(app)).putString("ok_note", gpuNote).apply(); }
            catch (Throwable e) { AppLog.e(statusLine()); sp.edit().remove("ok_key").apply(); }
            // nothing stays in memory; a job rebuilds it in well under a second. code 32: not while a job runs - the job may
            // already be using this same cached engine (Continue right after opening the app), and closing it under the
            // job's detect() calls was a native crash
            finally { if (!JobManager.get().isRunning()) release(); }
        }, "face-selfcheck");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** code 33: what the startup check result depends on: app build, Android build, the GPU verdict */
    static String selfCheckKey(Context app) {
        android.content.SharedPreferences sp = app.getSharedPreferences("face_gpu", Context.MODE_PRIVATE);
        return com.viral.shortsmaker.BuildConfig.VERSION_CODE + "|" + android.os.Build.FINGERPRINT + "|" + sp.getBoolean("bad", false) + "|" + sp.getBoolean("trying", false);
    }

    /** code 32: true when the app's previous main process ended in a crash (or Android can't tell: below 11 / no record) */
    static boolean lastExitWasCrash(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 30) return true;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 10);
            if (l != null) for (android.app.ApplicationExitInfo x : l) {
                if (x.getPid() == android.os.Process.myPid() || !ctx.getPackageName().equals(x.getProcessName())) continue;
                int r = x.getReason();
                return r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                        || r == android.app.ApplicationExitInfo.REASON_ANR;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static String statusLine() {
        String abi = android.os.Build.SUPPORTED_ABIS != null && android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?";
        if (engineState > 0) return "Face engine: ready (" + abi + (gpuNote.isEmpty() ? "" : ", " + gpuNote) + ", loaded in " + engineMs + " ms)";
        if (engineState < 0) return "Face engine: FAILED on this phone (" + abi + "): " + engineError + " — shorts use normal framing";
        return "Face engine: not checked yet";
    }

    /** builds (once) and returns the face landmarker; throws with the full reason when the engine cannot run */
    static FaceLandmarker ensureEngine(Context ctx) throws Exception {
        synchronized (LOCK) {
            if (engineState < 0) throw new Exception(engineError);
            if (cachedFace != null) return cachedFace;
            long t0 = System.currentTimeMillis();
            try {
                // the class whose static init failed in 2.4 (R8 + Flogger); load it explicitly so the log names it
                Class.forName("com.google.mediapipe.framework.Graph");
                // v16 (2.7): the phone's GPU first (face model several times faster than on the CPU), the CPU when the
                // GPU cannot run it. Crash guard: a flag is written before the GPU attempt and cleared after it; if
                // the app ever died during the attempt, the flag is still there next time and the GPU is never tried
                // again on this phone.
                FaceLandmarker fl = null;
                android.content.SharedPreferences sp = ctx.getSharedPreferences("face_gpu", Context.MODE_PRIVATE);
                // code 32: only a CRASH during the GPU attempt marks the GPU bad (Android 11+ says how the app ended); a
                // plain kill (the phone closing a background start, a swipe) no longer moves face tracking to the CPU for good
                if (sp.getBoolean("trying", false)) {
                    boolean crashed = lastExitWasCrash(ctx);
                    sp.edit().putBoolean("bad", sp.getBoolean("bad", false) || crashed).putBoolean("trying", false).commit();
                }
                gpuNote = "";
                if (!sp.getBoolean("bad", false) && android.os.Build.VERSION.SDK_INT >= 26) {
                    sp.edit().putBoolean("trying", true).commit();
                    try {
                        fl = build(ctx, Delegate.GPU);
                        Bitmap probe = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
                        fl.detect(new BitmapImageBuilder(probe).build());   // one real inference proves the GPU path
                        probe.recycle();
                        gpuNote = "GPU";
                    } catch (Throwable g) {
                        try { if (fl != null) fl.close(); } catch (Throwable ignored) {}
                        fl = null;
                        gpuNote = "CPU (GPU not usable: " + causeChain(g) + ")";
                        sp.edit().putBoolean("bad", true).commit();
                    } finally {
                        sp.edit().putBoolean("trying", false).commit();
                    }
                } else gpuNote = sp.getBoolean("bad", false) ? "CPU (GPU off on this phone)" : "CPU";
                if (fl == null) fl = build(ctx, Delegate.CPU);
                cachedFace = fl;
                engineMs = System.currentTimeMillis() - t0;
                engineState = 1;
                return cachedFace;
            } catch (Throwable e) {
                engineError = causeChain(e);
                engineState = -1;
                throw new Exception(engineError, e);
            }
        }
    }

    static volatile String gpuNote = "";

    /** code 31: the face engine runs on the GPU (RenderSafety's crash marker) */
    public static boolean onGpu() { return "GPU".equals(gpuNote); }

    /** code 32: builds the engine (if it can be) so onGpu() is known before a job's face pass starts */
    public static void prepare(Context ctx) { try { ensureEngine(ctx); } catch (Throwable ignored) {} }

    static FaceLandmarker build(Context ctx, Delegate d) throws Exception {
        return FaceLandmarker.createFromOptions(ctx, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(asset(ctx, "ft/face_landmarker.task")).setDelegate(d).build())
                .setRunningMode(RunningMode.IMAGE).setNumFaces(4)
                .setMinFaceDetectionConfidence(0.4f).setMinFacePresenceConfidence(0.4f).setMinTrackingConfidence(0.4f)
                .build());
    }

    /** v22: 2 s of frames per chunk handed to one engine */
    static final int CHUNK = FPS * 2;
    private static final List<FaceLandmarker> extraFace = new ArrayList<>();   // guarded by LOCK (built under it)
    private static final List<PoseLandmarker> extraPose = new ArrayList<>();

    /**
     * v22 (4.1): face engines used at once on this phone: 3 on 8 cores (the renders and the encoder keep the rest), 2 on
     * 6, 1 on 4 or fewer, on a low-RAM phone, and on the GPU (one GPU queue: more instances do not add speed).
     */
    static int workerCount() { return workerCountFor(Runtime.getRuntime().availableProcessors(), lowRam, "GPU".equals(gpuNote)); }
    public static int workerCountFor(int cores, boolean lowRamPhone, boolean gpu) {
        if (gpu || lowRamPhone || cores <= 4) return 1;
        return Math.max(1, Math.min(3, (cores - 2) / 2));
    }
    static volatile boolean lowRam;

    /** engine number k (0 = the checked one; the others are built on the same delegate the first time they are needed) */
    static FaceLandmarker engine(Context ctx, int k) throws Exception {
        FaceLandmarker first = ensureEngine(ctx);
        if (k == 0) return first;
        synchronized (extraFace) {
            while (extraFace.size() < k) extraFace.add(build(ctx, "GPU".equals(gpuNote) ? Delegate.GPU : Delegate.CPU));
            return extraFace.get(k - 1);
        }
    }

    static PoseLandmarker pose(Context ctx, int k) throws Exception {
        if (k == 0) return pose(ctx);
        synchronized (extraPose) {
            while (extraPose.size() < k) extraPose.add(buildPose(ctx));
            return extraPose.get(k - 1);
        }
    }

    static PoseLandmarker buildPose(Context ctx) throws Exception {
        return PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(asset(ctx, "ft/pose_landmarker_lite.task")).build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(4)
                .setMinPoseDetectionConfidence(0.4f).setMinPosePresenceConfidence(0.4f).setMinTrackingConfidence(0.4f)
                .build());
    }

    /**
     * v22 (4.1): when the body model runs on a wide-shot frame where the face model found nobody. Same shot (no cut since
     * the last body check): right after it found people, the next frame reuses those heads; after it found nobody, it
     * looks again only every STRIDE frames (~0.6 s). A cut always gets a fresh check.
     */
    public static final class PoseGate {
        public static final int SKIP = 0, REUSE = 1, RUN = 2;
        int last = -1000; boolean cutSince = true;
        public List<FaceTrack.Face> heads = new ArrayList<>();
        public void frame(boolean cutHere) { if (cutHere) cutSince = true; }
        public int decide(int i) {
            if (!cutSince && last >= 0) {
                if (!heads.isEmpty() && i - last < 2) return REUSE;
                if (heads.isEmpty() && i - last < STRIDE) return SKIP;
            }
            return RUN;
        }
        public void ran(int i, List<FaceTrack.Face> found) { last = i; cutSince = false; heads = found == null ? new ArrayList<>() : found; }
    }

    static PoseLandmarker pose(Context ctx) throws Exception {
        if (cachedPose == null) cachedPose = PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(asset(ctx, "ft/pose_landmarker_lite.task")).build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(4)
                .setMinPoseDetectionConfidence(0.4f).setMinPosePresenceConfidence(0.4f).setMinTrackingConfidence(0.4f)
                .build());
        return cachedPose;
    }

    /** frees the models after a render batch (they are rebuilt in well under a second next time) */
    public static void release() {
        synchronized (LOCK) {
            try { if (cachedFace != null) cachedFace.close(); } catch (Throwable ignored) {}
            try { if (cachedPose != null) cachedPose.close(); } catch (Throwable ignored) {}
            cachedFace = null; cachedPose = null;
            synchronized (extraFace) { for (FaceLandmarker f : extraFace) { try { f.close(); } catch (Throwable ignored) {} } extraFace.clear(); }
            synchronized (extraPose) { for (PoseLandmarker f : extraPose) { try { f.close(); } catch (Throwable ignored) {} } extraPose.clear(); }
        }
    }

    /**
     * v14: the engine is checked FIRST (2.4 extracted every frame from the network stream and only then found the
     * engine broken: ~2 min of dead time per short). Frames + audio level are extracted in parallel and outside the
     * lock (each short has its own folder), only the model calls are serialised.
     */
    public static Result analyze(Context ctx, String video, String audio, double start, double dur, File work,
                                 CancelToken ct, Tick tick) throws Exception {
        return analyze(ctx, video, audio, start, dur, work, "0", ct, tick);
    }

    public static Result analyze(Context ctx, String video, String audio, double start, double dur, File work, String tag,
                                 CancelToken ct, Tick tick) throws Exception {
        long t0 = System.currentTimeMillis();
        ensureEngine(ctx);   // fail fast, before any decoding
        File dir = new File(work, "ft_frames_" + tag);
        deleteDir(dir);
        dir.mkdirs();
        File pcm = new File(work, "ft_audio_" + tag + ".pcm");
        pcm.delete();
        try {
            // 2. audio loudness in parallel with the frames (optional: no audio = every frame counts as voiced)
            final float[][] pcmHolder = new float[1][];
            Thread at = new Thread(() -> {
                try {
                    List<String> b = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
                    FfmpegCommands.addInput(b, audio != null ? audio : video, start, dur);
                    b.addAll(Arrays.asList("-map", "0:a:0", "-vn", "-ac", "1", "-ar", "8000", "-f", "s16le", pcm.getAbsolutePath()));
                    MediaRunner.run(b.toArray(new String[0]), dur, null, ct);
                    pcmHolder[0] = readPcm(pcm);
                } catch (Throwable e) {
                    if (!ct.cancelled) AppLog.i("Face tracking: no audio level (" + e.getMessage() + "), using faces only");
                }
            }, "ft-audio");
            at.start();
            // 1. frames (5 fps, 640 wide). Same input seek as the render, so frame i is at i/5 s of the short.
            // v16 (2.7): FFmpeg writes the frames in its own thread and each frame is analysed as soon as the next one
            // exists (2.6 waited for all frames first). The decoder skips its deblocking pass (not needed to find faces).
            List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-threads", "3", "-skip_loop_filter", "all", "-flags2", "+fast"));
            FfmpegCommands.addInput(a, video, start, dur);
            a.addAll(Arrays.asList("-map", "0:v:0", "-vf", FfmpegCommands.colourIn("fps=" + FPS + ",scale=" + W + ":-2:flags=fast_bilinear"), "-q:v", "5",   // code 35: colour tag (no RGB round trip)
                    "-an", "-sn", "-dn", new File(dir, "f%05d.jpg").getAbsolutePath()));
            final Throwable[] ffErr = new Throwable[1];
            final boolean[] ffDone = {false};
            Thread ft = new Thread(() -> {
                try { MediaRunner.run(a.toArray(new String[0]), dur, null, ct); }
                catch (Throwable e) { ffErr[0] = e; }
                finally { synchronized (ffDone) { ffDone[0] = true; } }
            }, "ft-frames");
            ft.start();
            final int expect = Math.max(3, (int) Math.ceil(dur * FPS));
            Result res = new Result();
            try {
                synchronized (LOCK) {
                    ensureEngine(ctx);
                    // v22 (4.1): the face model uses ~1 CPU core per call (measured: 1.1 cores busy), so 4.0 analysed a
                    // short on one core of eight while the others waited. Now K engines work on 2-second chunks of the
                    // same short at once (each engine its own model instance: ~38 MB each). Box: 3 engines = 2.95x the
                    // frames per second of one. Chunks are taken in order, so the frames are read as FFmpeg writes them.
                    try { android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE); lowRam = am != null && am.isLowRamDevice(); } catch (Throwable ignored) {}
                    int kk = workerCount();
                    // every engine is built here, on this thread (it holds LOCK, which ensureEngine needs)
                    final FaceLandmarker[] engines = new FaceLandmarker[kk];
                    for (int e = 0; e < kk; e++) {
                        try { engines[e] = engine(ctx, e); }
                        catch (Throwable be) { if (e == 0) { if (be instanceof Exception) throw (Exception) be; throw new Exception(causeChain(be), be); } kk = e; AppLog.i("Face tracking: using " + e + " face engine(s) (" + causeChain(be) + ")"); break; }
                    }
                    final int k = kk;
                    if (tick != null) tick.on(0);   // v22: the analysis itself starts now (after any wait for the engines)
                    final int cap = expect + 64;
                    final FaceTrack.Sample[] out = new FaceTrack.Sample[cap];
                    final float[][] thumbs = new float[cap][];
                    final java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger();
                    final java.util.concurrent.atomic.AtomicInteger maxRead = new java.util.concurrent.atomic.AtomicInteger(-1);
                    final java.util.concurrent.atomic.AtomicInteger doneFrames = new java.util.concurrent.atomic.AtomicInteger();
                    final java.util.concurrent.atomic.AtomicInteger endAt = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
                    final int[][] calls = new int[k][4];   // face, tile, pose, reused heads
                    final Throwable[] wErr = new Throwable[1];
                    final int[] srcWH = new int[2];
                    Thread[] ws = new Thread[k];
                    for (int wk = 0; wk < k; wk++) {
                        final int me = wk;
                        Runnable job = () -> {
                            try {
                                FaceLandmarker fl = engines[me];
                                while (true) {
                                    int c = nextChunk.getAndIncrement();
                                    int i0 = c * CHUNK;
                                    if (i0 >= Math.min(cap, endAt.get())) return;
                                    float[] prevThumb = null;
                                    int tileMiss = 0;
                                    PoseGate gate = new PoseGate();
                                    for (int i = i0; i < i0 + CHUNK && i < cap; i++) {
                                        ct.check();
                                        if (wErr[0] != null || i >= endAt.get()) return;
                                        File fi = new File(dir, String.format(Locale.US, "f%05d.jpg", i + 1)), next = new File(dir, String.format(Locale.US, "f%05d.jpg", i + 2));
                                        // frame i is complete once frame i+1 exists (or was already read by another engine) or FFmpeg has finished
                                        while (true) {
                                            boolean done; synchronized (ffDone) { done = ffDone[0]; }
                                            if (done || next.exists() || maxRead.get() > i) break;
                                            ct.check();
                                            Thread.sleep(10);
                                        }
                                        if (!fi.exists()) { endAt.accumulateAndGet(i, Math::min); return; }
                                        maxRead.accumulateAndGet(i, Math::max);
                                        Bitmap bmp = BitmapFactory.decodeFile(fi.getAbsolutePath());
                                        fi.delete();
                                        if (bmp == null) { out[i] = new FaceTrack.Sample(null, 0, 0); doneFrames.incrementAndGet(); continue; }
                                        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) { Bitmap c2 = bmp.copy(Bitmap.Config.ARGB_8888, false); bmp.recycle(); bmp = c2; }
                                        int w = bmp.getWidth(), h = bmp.getHeight();
                                        if (i == 0) { srcWH[0] = w; srcWH[1] = h; }
                                        List<FaceTrack.Face> faces = faces(fl, bmp);
                                        calls[me][0]++;
                                        float[] th = thumb(bmp);
                                        boolean cutHere = prevThumb != null && diff(prevThumb, th) > FaceTrack.CUT_ABS;
                                        if (cutHere) tileMiss = 0;
                                        gate.frame(cutHere);
                                        boolean small = true;
                                        for (FaceTrack.Face f : faces) if (f.h() > 0.3) { small = false; break; }
                                        // v18 (2.9) small or side-on faces in a wide shot: the face model again on two overlapping halves
                                        // v19 (3.0) halves that found nobody new 3 times running: every ~0.6 s only, until a cut or a find
                                        boolean tileNow = tileMiss < 3 || i % STRIDE == 0;
                                        if (w > h * 1.2 && small && lipFaces(faces) < 2 && tileNow) {
                                            int before = faces.size();
                                            for (float[] tx : TILES) {
                                                int x0 = (int) (w * tx[0]), x1 = Math.min(w, (int) (w * tx[1]));
                                                Bitmap tile = Bitmap.createBitmap(bmp, x0, 0, x1 - x0, h);
                                                calls[me][1]++;
                                                try {
                                                    float tw = x1 - x0;
                                                    for (FaceTrack.Face g : faces(fl, tile)) {
                                                        FaceTrack.Face m = new FaceTrack.Face((x0 + g.x0 * tw) / w, g.y0, (x0 + g.x1 * tw) / w, g.y1, g.mouth);
                                                        if (!dupTight(faces, m)) faces.add(m);
                                                    }
                                                } finally { if (tile != bmp) tile.recycle(); }
                                            }
                                            tileMiss = faces.size() > before ? 0 : tileMiss + 1;
                                            for (FaceTrack.Face f : faces) if (f.h() > 0.3) { small = false; break; }
                                        }
                                        // body model (2.3x the face model's cost) only in wide shots with small faces.
                                        // v22 (4.1): his short #2 ran it on 456 of 493 frames (a shot with nobody's face visible).
                                        // Now, in the same shot: a moment after it found nobody, it waits ~0.6 s before looking
                                        // again; right after it found people, the next frame reuses those heads (0.125 s later)
                                        if (w > h * 1.2 && small && faces.isEmpty()) {
                                            int dcs = gate.decide(i);
                                            if (dcs == PoseGate.REUSE) { for (FaceTrack.Face g : gate.heads) faces.add(g); calls[me][3]++; }
                                            else if (dcs == PoseGate.RUN) {
                                                List<FaceTrack.Face> hs = new ArrayList<>();
                                                try { calls[me][2]++; for (FaceTrack.Face g : heads(pose(ctx, me), bmp)) if (!dup(faces, g)) { faces.add(g); hs.add(g); } }
                                                catch (Throwable pe) { if (calls[me][2] == 1) AppLog.i("Face tracking: body model unavailable (" + causeChain(pe) + ")"); }
                                                gate.ran(i, hs);
                                            }
                                        } else if (w > h * 1.2 && small && faces.size() < 2 && i % STRIDE == 0) {
                                            try { calls[me][2]++; for (FaceTrack.Face g : heads(pose(ctx, me), bmp)) if (!dup(faces, g)) faces.add(g); }
                                            catch (Throwable pe) { if (calls[me][2] == 1) AppLog.i("Face tracking: body model unavailable (" + causeChain(pe) + ")"); }
                                        }
                                        bmp.recycle();
                                        thumbs[i] = th;
                                        out[i] = new FaceTrack.Sample(faces, 0, 0);
                                        prevThumb = th;
                                        int dn = doneFrames.incrementAndGet();
                                        if (tick != null) tick.on(Math.min(1.0, dn / (double) expect));
                                    }
                                }
                            } catch (CancelToken.Cancelled cc) {
                                synchronized (out) { if (wErr[0] == null) wErr[0] = cc; }
                            } catch (Throwable e) {
                                synchronized (out) { if (wErr[0] == null) wErr[0] = e; }
                            }
                        };
                        if (wk == k - 1) job.run();
                        else { ws[wk] = new Thread(job, "ft-engine-" + wk); ws[wk].start(); }
                    }
                    for (Thread t : ws) if (t != null) t.join();
                    if (wErr[0] instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) wErr[0];
                    if (wErr[0] instanceof Exception) throw (Exception) wErr[0];
                    if (wErr[0] != null) throw new Exception(causeChain(wErr[0]), wErr[0]);
                    int n = Math.min(cap, endAt.get());
                    while (n > 0 && out[n - 1] == null) n--;
                    res.srcW = srcWH[0]; res.srcH = srcWH[1];
                    float[] prev = null;
                    for (int i = 0; i < n; i++) {
                        FaceTrack.Sample sm = out[i] == null ? new FaceTrack.Sample(null, 0, 0) : out[i];
                        float cut = prev == null || thumbs[i] == null ? 0 : diff(prev, thumbs[i]);
                        res.samples.add(new FaceTrack.Sample(sm.faces, 0, cut));
                        if (thumbs[i] != null) prev = thumbs[i];
                    }
                    for (int[] cc : calls) { res.faceCalls += cc[0]; res.tileCalls += cc[1]; res.poseCalls += cc[2]; res.reusedHeads += cc[3]; }
                    res.engines = k;
                }
            } finally {
                if (ft.isAlive()) { ft.join(ct.cancelled ? 2000 : 120000); }
            }
            if (ffErr[0] instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) ffErr[0];
            // code 32: the video engine ended mid-clip (another short's crash / a phone kill): the frames stop part-way. 4.9
            // planned (and cached) tracking from that partial clip - the camera froze after that point
            if (ffErr[0] instanceof MediaRunner.EngineCrash) throw new Exception("frame extraction stopped: the video engine restarted", ffErr[0]);
            if (res.samples.size() < 3) throw new Exception("no video frames" + (ffErr[0] != null ? " (" + ffErr[0].getMessage() + ")" : ""));
            at.join(60000);
            float[] pcmData = pcmHolder[0];
            // audio level per sample, now that the audio thread is done
            List<FaceTrack.Sample> withAudio = new ArrayList<>(res.samples.size());
            for (int i = 0; i < res.samples.size(); i++) {
                FaceTrack.Sample sm = res.samples.get(i);
                withAudio.add(new FaceTrack.Sample(sm.faces, level(pcmData, i), sm.cut));
            }
            res.samples = withAudio;
            res.seconds = (System.currentTimeMillis() - t0) / 1000.0;
            return res;
        } finally {
            deleteDir(dir);
            pcm.delete();
        }
    }

    static List<FaceTrack.Face> faces(FaceLandmarker fl, Bitmap bmp) {
        List<FaceTrack.Face> out = new ArrayList<>();
        MPImage img = new BitmapImageBuilder(bmp).build();
        FaceLandmarkerResult r = fl.detect(img);
        float W = bmp.getWidth(), H = bmp.getHeight();
        for (List<NormalizedLandmark> L : r.faceLandmarks()) {
            if (L.size() < 153) continue;
            float x0 = 9, y0 = 9, x1 = -9, y1 = -9;
            for (NormalizedLandmark p : L) { x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y()); x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y()); }
            double fh = Math.hypot((L.get(10).x() - L.get(152).x()) * W, (L.get(10).y() - L.get(152).y()) * H);
            double mo = Math.hypot((L.get(13).x() - L.get(14).x()) * W, (L.get(13).y() - L.get(14).y()) * H) / Math.max(fh, 1e-6);
            out.add(new FaceTrack.Face(x0, y0, x1, y1, (float) mo));
        }
        return out;
    }

    /** head boxes from the pose model (landmarks 0-10 = nose, eyes, ears, mouth corners), no lip value (-1) */
    static List<FaceTrack.Face> heads(PoseLandmarker pl, Bitmap bmp) {
        List<FaceTrack.Face> out = new ArrayList<>();
        PoseLandmarkerResult r = pl.detect(new BitmapImageBuilder(bmp).build());
        float W = bmp.getWidth(), H = bmp.getHeight();
        for (List<NormalizedLandmark> L : r.landmarks()) {
            if (L.size() < 13) continue;
            double cx = 0, cy = 0;
            for (int k = 0; k < 11; k++) { cx += L.get(k).x(); cy += L.get(k).y(); }
            cx /= 11; cy /= 11;
            double ear = Math.hypot((L.get(7).x() - L.get(8).x()) * W, (L.get(7).y() - L.get(8).y()) * H);
            double sh = Math.hypot((L.get(11).x() - L.get(12).x()) * W, (L.get(11).y() - L.get(12).y()) * H);
            double hw = Math.max(Math.max(ear * 1.1, sh * 0.42), 8.0) / W, hh = hw * W * 1.3 / H;
            out.add(new FaceTrack.Face((float) (cx - hw / 2), (float) (cy - hh / 2), (float) (cx + hw / 2), (float) (cy + hh / 2), -1f));
        }
        return out;
    }

    /** v18: the two overlapping halves used for small faces in wide shots */
    static final float[][] TILES = {{0f, 0.58f}, {0.42f, 1f}};

    public static int lipFacesForTest(List<FaceTrack.Face> fs) { return lipFaces(fs); }
    public static boolean dupTightForTest(List<FaceTrack.Face> fs, FaceTrack.Face g) { return dupTight(fs, g); }
    static int lipFaces(List<FaceTrack.Face> fs) { int n = 0; for (FaceTrack.Face f : fs) if (f.mouth >= 0) n++; return n; }

    /** v18: the same face found twice (whole frame and a half, or both halves): centres closer than ~0.6 face sizes */
    static boolean dupTight(List<FaceTrack.Face> fs, FaceTrack.Face g) {
        for (FaceTrack.Face e : fs)
            if (Math.abs(e.cx() - g.cx()) < 0.6 * e.w() + 0.01 && Math.abs(e.cy() - g.cy()) < 0.6 * e.h() + 0.02) return true;
        return false;
    }

    static boolean dup(List<FaceTrack.Face> fs, FaceTrack.Face g) {
        for (FaceTrack.Face e : fs)
            if (Math.abs(e.cx() - g.cx()) < 0.8 * e.w() + 0.02 && Math.abs(e.cy() - g.cy()) < 0.8 * e.h() + 0.03) return true;
        return false;
    }

    static float[] thumb(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 32, 18, true);
        int[] px = new int[32 * 18];
        s.getPixels(px, 0, 32, 0, 0, 32, 18);
        if (s != b) s.recycle();
        float[] l = new float[px.length];
        for (int i = 0; i < px.length; i++) { int c = px[i]; l[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255); }
        return l;
    }

    static float diff(float[] a, float[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]); return (float) (s / a.length); }

    /** RMS of the 0.2 s of audio centred on sample i */
    static float level(float[] pcm, int i) {
        if (pcm == null || pcm.length == 0) return 0;
        int c = i * 8000 / FPS, hw = 8000 / FPS / 2;
        int a = Math.max(0, c - hw), b = Math.min(pcm.length, c + hw);
        if (b <= a) return 0;
        double s = 0;
        for (int k = a; k < b; k++) s += pcm[k] * pcm[k];
        return (float) Math.sqrt(s / (b - a));
    }

    static float[] readPcm(File f) throws Exception {
        if (!f.exists() || f.length() < 2) return null;
        byte[] all = new byte[(int) Math.min(f.length(), 8000L * 2 * 400)];
        int n = 0;
        try (InputStream in = new FileInputStream(f)) { int r; while (n < all.length && (r = in.read(all, n, all.length - n)) > 0) n += r; }
        ByteBuffer bb = ByteBuffer.wrap(all, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n / 2];
        for (int i = 0; i < out.length; i++) out[i] = bb.getShort() / 32768f;
        return out;
    }

    static ByteBuffer asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(4 << 20);
            byte[] b = new byte[1 << 16]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            byte[] all = bo.toByteArray();
            ByteBuffer bb = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
            bb.put(all); bb.rewind();
            return bb;
        }
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }
}

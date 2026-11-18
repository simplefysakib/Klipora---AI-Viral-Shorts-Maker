package com.viral.shortsmaker;

import com.viral.shortsmaker.core.CaptionScript;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.viral.shortsmaker.captions.CaptionFonts;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Net;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.ProcessingService;
import com.viral.shortsmaker.core.Project;
import com.viral.shortsmaker.core.Transcript;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/** Pick a caption style and draw the caption area on the real video frame, with a live animated preview. */
public class CaptionStudioActivity extends AppCompatActivity {
    /** v17: same compact UI scale on every screen */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiScale.wrap(base)); }

    public static final String EXTRA_CLIP = "clip";      // -1 = default style for new shorts, -2 = all clips
    /** frame for default mode (set by MainActivity from the fetched thumbnail / local frame) */
    public static volatile Bitmap pendingFrame;
    public static volatile FfmpegCommands.Frame pendingFrameMode = FfmpegCommands.Frame.BLUR;
    /** v14: format chosen on the create screen (for the default-style studio) */
    public static volatile FfmpegCommands.Aspect pendingAspect = FfmpegCommands.Aspect.PORTRAIT;
    private FfmpegCommands.Aspect studioAspect = FfmpegCommands.Aspect.PORTRAIT;

    private static final int[] TEXT_COLORS = {0xFFFFFFFF, 0xFFFFE500, 0xFFFFD60A, 0xFF3DFF5A, 0xFF22D3EE, 0xFFFF4D8D, 0xFFE0FCFF, 0xFF111111};
    private static final int[] HL_COLORS = {0xFFFFE500, 0xFF3DFF5A, 0xFF22D3EE, 0xFF7C3AED, 0xFFFF2BD6, 0xFFFF3B5C, 0xFFF97316, 0xFFFFFFFF};

    private CaptionPreviewView preview;
    private CaptionStyle style;
    private CaptionLayout layout;
    private int clipIdx;
    private boolean binding;
    private PremiumSlider sliderSize, sliderWords;
    private TextView segLines1, segLines2;
    private ChipGroup fontGroup, linesGroup;
    private MaterialSwitch swUpper, swKeywords, swProgress;
    private String script = CaptionScript.HINGLISH;
    private List<com.viral.shortsmaker.captions.Word> rawWords;
    private Transcript rawTr;
    private TextView tvSize, tvWords;
    private StyleAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        script = Prefs.captionScript(this);   // v7: set before the first preview (null crashed v6 on Android 16)
        setContentView(R.layout.activity_caption_studio);
        Ui.fitSystemBars(this);
        clipIdx = getIntent().getIntExtra(EXTRA_CLIP, -1);
        style = Prefs.captionStyle(this);
        // v14: the box is edited on the frame of the format the shorts are made in
        Project prj = JobManager.get().project;
        studioAspect = clipIdx == -1 || prj == null ? pendingAspect : FfmpegCommands.Aspect.of(prj.aspect);
        layout = Prefs.captionLayout(this, studioAspect);

        preview = findViewById(R.id.preview);
        preview.setStyle(style);
        preview.setCaptionLayout(layout);
        preview.setOnLayoutChange(l -> { layout = l.copy(); findViewById(R.id.chipGroupPos).post(() -> ((ChipGroup) findViewById(R.id.chipGroupPos)).clearCheck()); });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        TextView tvFor = findViewById(R.id.tvStudioFor);
        MaterialButton primary = findViewById(R.id.btnPrimary), secondary = findViewById(R.id.btnSecondary);
        MaterialButton remove = findViewById(R.id.btnRemove);
        Project pr = JobManager.get().project;

        if (clipIdx == -1) {
            tvFor.setText("Default style for new shorts");
            primary.setText("Save style");
            secondary.setVisibility(View.GONE);
            findViewById(R.id.spaceBtns).setVisibility(View.GONE);
            if (pendingFrame != null) preview.setFrame(pendingFrame, pendingFrameMode);
        } else {
            if (pr == null || pr.clips.isEmpty()) { finish(); return; }
            boolean all = clipIdx == -2;
            Project.Clip clip = pr.clips.get(all ? 0 : Math.min(clipIdx, pr.clips.size() - 1));
            tvFor.setText(all ? "All " + pr.clips.size() + " shorts" : "Short #" + (clip.index + 1));
            primary.setText(all ? "Apply to all" : "Apply");
            if (all || pr.clips.size() < 2) { secondary.setVisibility(View.GONE); findViewById(R.id.spaceBtns).setVisibility(View.GONE); }
            else secondary.setText("Apply to all");
            boolean anyCaptioned = false;
            for (Project.Clip c : pr.clips) if (!c.captionStyle.isEmpty()) anyCaptioned = true;
            if (all ? anyCaptioned : !clip.captionStyle.isEmpty()) remove.setVisibility(View.VISIBLE);
            loadFrameAndWords(pr, clip);
        }

        primary.setOnClickListener(v -> apply(clipIdx == -2 ? -2 : clipIdx));
        secondary.setOnClickListener(v -> apply(-2));
        remove.setOnClickListener(v -> startCaptionJob(clipIdx, true));

        setupControls();
        bindControls();
    }

    private void loadFrameAndWords(Project pr, Project.Clip clip) {
        String tj = Project.readText(pr.transcriptFile(this));
        if (tj != null) {
            Transcript t = Transcript.fromJson(tj);
            rawTr = t;
            // v9: the words heard in the rendered short itself (exact timing), else the full transcript
            java.util.List<com.viral.shortsmaker.captions.Word> own = clip.spokenJson != null && !clip.spokenJson.isEmpty()
                    && com.viral.shortsmaker.core.Pipeline.spokenKey(clip).equals(clip.spokenKey)
                    ? com.viral.shortsmaker.core.Pipeline.wordsFromJson(clip.spokenJson) : null;
            rawWords = own != null && !own.isEmpty() ? own : t.wordsForClip(clip.start, clip.end);
            if (own != null && !own.isEmpty()) { Transcript lt = new Transcript(); lt.language = clip.spokenLang; rawTr = lt; }
            showWordsInScript();
        }
        FfmpegCommands.Frame fm;
        try { fm = FfmpegCommands.Frame.valueOf(pr.frame); } catch (Exception e) { fm = FfmpegCommands.Frame.BLUR; }
        if (com.viral.shortsmaker.core.Pipeline.trackingWanted(pr)) fm = FfmpegCommands.Frame.FILL;   // v13: tracked shorts are full-height crops
        final FfmpegCommands.Frame mode = fm;
        new Thread(() -> {
            Bitmap bmp = null;
            FfmpegCommands.Frame m = mode;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (clip.captionStyle.isEmpty() && clip.file() != null && clip.file().exists()) {
                    r.setDataSource(clip.outPath);
                    bmp = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } else if (pr.localPath != null && !pr.localPath.isEmpty() && new File(pr.localPath).exists()) {
                    r.setDataSource(pr.localPath);
                    bmp = r.getFrameAtTime((long) ((clip.start + 1) * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } catch (Exception ignored) {
            } finally { try { r.release(); } catch (Exception ignored) {} }
            if (bmp == null && pr.thumbUrl != null && !pr.thumbUrl.isEmpty()) {
                try (Response resp = Net.fast().newCall(new Request.Builder().url(pr.thumbUrl).build()).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) { InputStream in = resp.body().byteStream(); bmp = BitmapFactory.decodeStream(in); }
                } catch (Exception ignored) {}
            }
            final Bitmap fb = bmp;
            final FfmpegCommands.Frame fmode = m;
            runOnUiThread(() -> { if (!isFinishing()) preview.setFrame(fb, fmode); });
        }).start();
    }

    private void setupControls() {
        RecyclerView rv = findViewById(R.id.rvStyles);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        adapter = new StyleAdapter(CaptionStyles.all());
        rv.setAdapter(adapter);

        fontGroup = findViewById(R.id.chipGroupFont);
        for (CaptionFonts.Font f : CaptionFonts.ALL) {
            Chip c = new Chip(this);
            c.setText(f.label);
            c.setCheckable(true);
            c.setTypeface(CaptionPreviewView.typeface(this, f));
            c.setTag(f.key);
            c.setId(View.generateViewId());
            fontGroup.addView(c);
        }
        fontGroup.setOnCheckedStateChangeListener((g, ids) -> {
            if (binding || ids.isEmpty()) return;
            Chip c = g.findViewById(ids.get(0));
            style.fontKey = (String) c.getTag();
            push();
        });

        sliderSize = findViewById(R.id.sliderSize);
        sliderSize.setValueFrom(CaptionPreviewView.SIZE_MIN); sliderSize.setValueTo(CaptionPreviewView.SIZE_MAX); sliderSize.setStepSize(0.01f);
        sliderSize.setValue(1f);
        tvSize = findViewById(R.id.tvSizeValue);
        // v12: the slider and the box's corner handle are one control: both scale the text and the box together
        sliderSize.addOnChangeListener((s, v, user) -> {
            tvSize.setText(Math.round(v * 100) + "%");
            if (user && !binding) {
                preview.setSizeScale(v);
                style.sizeScale = v;
                layout = preview.getCaptionLayout().copy();
                push();
            }
        });
        preview.setOnSizeChange((v, done) -> {
            style.sizeScale = v;
            binding = true;
            sliderSize.setValue(v);
            binding = false;
            tvSize.setText(Math.round(v * 100) + "%");
            layout = preview.getCaptionLayout().copy();
            if (done) push();
        });
        sliderWords = findViewById(R.id.sliderWords);
        sliderWords.setValueFrom(1); sliderWords.setValueTo(8); sliderWords.setStepSize(1); sliderWords.setValue(3);
        tvWords = findViewById(R.id.tvWordsValue);
        sliderWords.addOnChangeListener((s, v, user) -> { tvWords.setText(String.valueOf((int) v)); if (user && !binding) { style.maxWordsPerLine = (int) v; push(); } });
        segLines1 = findViewById(R.id.segLines1);
        segLines2 = findViewById(R.id.segLines2);
        View.OnClickListener seg = v -> {
            if (binding) return;
            int lines = v.getId() == R.id.segLines2 ? 2 : 1;
            setLinesUi(lines);
            if (style.maxLines != lines) { style.maxLines = lines; push(); }
        };
        segLines1.setOnClickListener(seg);
        segLines2.setOnClickListener(seg);
        swUpper = findViewById(R.id.switchUpper);
        swUpper.setOnCheckedChangeListener((v, on) -> { if (!binding) { style.uppercase = on; push(); } });
        swKeywords = findViewById(R.id.switchKeywords);
        swProgress = findViewById(R.id.switchProgress);
        swKeywords.setOnCheckedChangeListener((v, on) -> { if (!binding) { style.keywords = on; push(); } });
        swProgress.setOnCheckedChangeListener((v, on) -> { if (!binding) { style.progressBar = on; push(); } });
        script = Prefs.captionScript(this);
        // v44: one searchable list of all 29 caption languages instead of a row of chips
        MaterialButton pick = findViewById(R.id.btnScriptPick);
        pick.setText(LanguagePicker.labelFor(script));
        pick.setOnClickListener(v -> LanguagePicker.show(this, script, s -> {
            script = s;
            pick.setText(LanguagePicker.labelFor(s));
            showWordsInScript();
            updateCaps();
        }));
        showWordsInScript();
        updateCaps();

        colorRow(findViewById(R.id.rowTextColors), TEXT_COLORS, true);
        colorRow(findViewById(R.id.rowHlColors), HL_COLORS, false);

        ChipGroup pos = findViewById(R.id.chipGroupPos);
        pos.setOnCheckedStateChangeListener((g, ids) -> {
            if (binding || ids.isEmpty()) return;
            int id = ids.get(0);
            float cy = id == R.id.chipPosTop ? 0.18f : id == R.id.chipPosMid ? 0.5f : id == R.id.chipPosLow ? 0.70f : 0.84f;
            layout.cy = cy;
            layout.clamp();
            preview.setCaptionLayout(layout);
        });
    }

    private void colorRow(LinearLayout row, int[] colors, boolean text) {
        int size = (int) (getResources().getDisplayMetrics().density * 32);
        int m = (int) (getResources().getDisplayMetrics().density * 8);
        for (int c : colors) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(m);
            v.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            d.setStroke((int) (getResources().getDisplayMetrics().density * 2), 0xFF2A2A3B);
            v.setBackground(d);
            v.setOnClickListener(x -> {
                if (text) style.textColor = c; else style.highlightColor = c;
                push();
            });
            row.addView(v);
        }
    }

    private void setLinesUi(int lines) {
        segLines1.setSelected(lines < 2);
        segLines2.setSelected(lines >= 2);
    }

    private void bindControls() {
        binding = true;
        for (int i = 0; i < fontGroup.getChildCount(); i++) {
            Chip c = (Chip) fontGroup.getChildAt(i);
            c.setChecked(style.fontKey.equals(c.getTag()));
        }
        sliderSize.setValue(CaptionPreviewView.clampSize(style.sizeScale));
        tvSize.setText(Math.round(style.sizeScale * 100) + "%");
        sliderWords.setValue(Math.max(1, Math.min(8, style.maxWordsPerLine)));
        tvWords.setText(String.valueOf(style.maxWordsPerLine));
        setLinesUi(style.maxLines >= 2 ? 2 : 1);
        swUpper.setChecked(style.uppercase);
        swKeywords.setChecked(style.keywords);
        swProgress.setChecked(style.progressBar);
        binding = false;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** v6: preview the real clip words in the chosen caption language (converted once, cached for the render too) */
    private void showWordsInScript() {
        if (preview == null) return;
        final String target = script;
        if (rawWords == null || rawWords.isEmpty()) { preview.setSampleWords(sampleFor(target)); return; }
        if (CaptionScript.action(rawWords, rawTr == null ? "" : rawTr.language, target).equals("keep")) { preview.setSampleWords(rawWords); return; }
        final List<com.viral.shortsmaker.captions.Word> src = rawWords;
        final Transcript tr = rawTr;
        new Thread(() -> {
            List<com.viral.shortsmaker.captions.Word> w;
            try { w = CaptionScript.convert(getApplicationContext(), new com.viral.shortsmaker.core.CancelToken(), tr, src, target, null); }
            catch (Throwable e) { w = src; }
            final List<com.viral.shortsmaker.captions.Word> fw = w;
            runOnUiThread(() -> { if (!isFinishing() && target.equals(script)) preview.setSampleWords(fw); });
        }, "caption-lang").start();
    }

    /** v11: ALL CAPS only changes Latin and Cyrillic letters, so it is greyed out (and says why) for scripts without case */
    private void updateCaps() {
        boolean noCase = CaptionScript.HINDI.equals(script) || (CaptionScript.isLang(script) && !com.viral.shortsmaker.captions.CaptionLangs.of(script).cased);
        swUpper.setEnabled(!noCase);
        TextView hint = findViewById(R.id.tvCapsHint);
        if (hint != null) hint.setText(noCase ? "Not used for " + CaptionScript.label(script) + " (its script has no capital letters)."
                : "Capital letters for Latin and Cyrillic captions. Other scripts stay as written.");
    }

    static List<com.viral.shortsmaker.captions.Word> sampleFor(String script) {
        if (CaptionScript.isLang(script)) {   // v44: the language's own sample line, split into its words (CJK, Thai: by piece)
            String[] parts = com.viral.shortsmaker.captions.CaptionLangs.sample(script).split(" ");
            List<com.viral.shortsmaker.captions.Word> l = new java.util.ArrayList<>();
            double t = 0.2;
            for (String s : parts) { double d = 0.30 + s.length() * 0.05; l.add(new com.viral.shortsmaker.captions.Word(s, t, t + d)); t += d + 0.06; }
            return com.viral.shortsmaker.captions.CaptionLangs.splitUnits(l, script);
        }
        String[] w = CaptionScript.HINDI.equals(script) ? new String[]{"ये", "है", "आपके", "कैप्शन", "का", "नया", "स्टाइल", "हर", "शॉर्ट", "पर!"}
                : CaptionScript.ENGLISH.equals(script) ? new String[]{"This", "is", "how", "your", "captions", "will", "look", "on", "every", "short!"}
                : new String[]{"Ye", "hai", "aapke", "captions", "ka", "naya", "style", "har", "short", "pe!"};
        List<com.viral.shortsmaker.captions.Word> l = new java.util.ArrayList<>();
        double t = 0.2;
        for (String s : w) { double d = 0.22 + s.length() * 0.035; l.add(new com.viral.shortsmaker.captions.Word(s, t, t + d)); t += d + 0.06; }
        return l;
    }

    private void push() {
        preview.setStyle(style);
    }

    private void apply(int target) {
        layout = preview.getCaptionLayout().copy();
        Prefs.saveCaption(this, style, layout);
        Prefs.setCaptionScript(this, script);
        if (target == -1) {
            Prefs.setCaptionsOn(this, true);
            setResult(RESULT_OK);
            finish();
            return;
        }
        startCaptionJob(target, false);
    }

    private void startCaptionJob(int target, boolean remove) {
        Project pr = JobManager.get().project;
        if (pr == null) { finish(); return; }
        if (JobManager.get().isRunning()) { Toast.makeText(this, "Please wait for the current job to finish", Toast.LENGTH_SHORT).show(); return; }
        Pipeline.Request rq = new Pipeline.Request();
        rq.kind = JobManager.Kind.CAPTION;
        rq.style = remove ? null : style.copy();
        rq.layout = layout.copy();
        rq.script = script;
        if (target < 0) for (int i = 0; i < pr.clips.size(); i++) rq.clipIndices.add(i);
        else rq.clipIndices.add(target);
        if (ProcessingService.start(this, rq)) {
            setResult(RESULT_OK);
            finish();
        }
    }

    // ------------------------------------------------------------------ styles carousel

    class StyleAdapter extends RecyclerView.Adapter<StyleAdapter.VH> {
        final List<CaptionStyle> items;
        StyleAdapter(List<CaptionStyle> items) { this.items = items; }

        class VH extends RecyclerView.ViewHolder {
            final CaptionPreviewView sw; final TextView name; final MaterialCardView card;
            VH(View v) { super(v); card = (MaterialCardView) v; sw = v.findViewById(R.id.swatch); name = v.findViewById(R.id.tvStyleName); sw.setSwatch(true); }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_style, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CaptionStyle s = items.get(pos);
            h.sw.setStyle(s);
            h.name.setText(s.name);
            boolean sel = s.id.equals(style.id);
            h.card.setStrokeColor(ColorStateList.valueOf(sel ? 0xFF7C5CFF : 0xFF2A2A3B));
            h.name.setTextColor(sel ? 0xFFFFFFFF : 0xFF9B9BB0);
            h.itemView.setOnClickListener(v -> {
                float keepScale = style.sizeScale;
                boolean kw = style.keywords, pb = style.progressBar;
                style = s.copy();
                style.sizeScale = keepScale;
                style.keywords = kw; style.progressBar = pb;   // v10: extras stay as chosen
                push();
                bindControls();
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }
}

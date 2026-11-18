package com.viral.shortsmaker.captions;

/** One spoken word with absolute timing in seconds (relative to whatever timeline the list belongs to). */
public class Word {
    public final String text;
    public final double start;
    public final double end;
    /** the spoken sentence ends after this word (kept when its punctuation is hidden in word-by-word styles) */
    public boolean sentenceEnd;
    /** v9: >= 0 when this word belongs to a translated phrase that is shown whole for the phrase's spoken time
     *  (English captions of Hindi speech can't be word-for-word); -1 = a real spoken word with its own timing */
    public int phrase = -1;

    public Word(String text, double start, double end) {
        this.text = text;
        this.start = start;
        this.end = Math.max(end, start);
    }

    public Word shift(double offset) { Word w = new Word(text, start + offset, end + offset); w.sentenceEnd = sentenceEnd; w.phrase = phrase; return w; }

    public Word withText(String t) { Word w = new Word(t, start, end); w.sentenceEnd = sentenceEnd; w.phrase = phrase; return w; }

    public Word retimed(double s, double e) { Word w = new Word(text, s, e); w.sentenceEnd = sentenceEnd; w.phrase = phrase; return w; }

    @Override public String toString() { return text + "[" + start + "-" + end + "]"; }
}

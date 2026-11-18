package com.viral.shortsmaker.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v26 (code 26): what a failed render was really about. Up to code 25 every failure of a render on the hardware encoder
 * was taken as "this phone's encoder refuses the quality settings": a dropped network stream during the first render
 * (common on mobile data) switched the phone to the bare encoder settings for good (default VBR, often Baseline =
 * blocky shorts on every later video), and a failure with a 1440p clip was blamed on its decoder, so two of them kept the
 * phone on the 1080p source for good (stretched, soft crops). Pure logic, unit tested.
 */
public final class RenderFault {
    private RenderFault() {}

    public enum Kind { NETWORK, ENCODER, DECODER, OTHER }

    private static final Pattern NET = Pattern.compile("(server returned|http error|connection (refused|reset|timed out|closed)|timed? ?out|"
            + "i/o error|input/output error|end of file|network is unreachable|failed to resolve|unable to resolve|could not resolve|"
            + "tls|ssl|broken pipe|reconnect|error number -1?10[0-9] |\\b(403|404|429|5\\d\\d) (forbidden|not found|too many|internal|bad gateway|service)|"
            + "stream ends prematurely|partial file|software caused connection abort)");
    private static final Pattern ENC = Pattern.compile("(vost#|error while opening encoder|could not open encoder|error initializing output stream|"
            + "unknown encoder|encoder (not found|failed)|hardware encoder|error submitting video frame to the encoder|"
            + "error encoding|nothing was written into output file|output did not play back|-codec_name)");
    private static final Pattern DEC = Pattern.compile("(vist#|\\[dec:|error while decoding|error opening (input|decoder)|decoding error|"
            + "decoder (not found|failed)|hardware decoder|error while opening decoder|invalid data found when processing input)");

    /**
     * httpInput = the render read a network stream (only then a network fault is possible);
     * log = FFmpeg's log (or the exception text).
     */
    public static Kind classify(String log, boolean httpInput) {
        if (log == null) return Kind.OTHER;
        String l = log.toLowerCase(Locale.US);
        if (httpInput && NET.matcher(l).find()) return Kind.NETWORK;
        boolean enc = ENC.matcher(l).find(), dec = DEC.matcher(l).find();
        if (enc && !dec) return Kind.ENCODER;
        if (dec && !enc) return Kind.DECODER;
        if (enc) {
            // both mentioned: the first error line decides (FFmpeg reports the cause first, the consequences after it)
            java.util.regex.Matcher me = ENC.matcher(l), md = DEC.matcher(l);
            me.find(); md.find();
            return me.start() <= md.start() ? Kind.ENCODER : Kind.DECODER;
        }
        return Kind.OTHER;
    }

    public static Kind classify(Exception e, boolean httpInput) {
        if (e == null) return Kind.OTHER;
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof MediaRunner.Failed) msg = ((MediaRunner.Failed) e).log + "\n" + msg;
        if (e.getClass().getSimpleName().equals("HwException")) return Kind.ENCODER;
        return classify(msg, httpInput);
    }
}

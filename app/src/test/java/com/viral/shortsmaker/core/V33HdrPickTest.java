package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** code 33 (5.1): an HDR stream is not picked over an SDR one of the same height (no tone mapping in the render) */
@RunWith(RobolectricTestRunner.class)
public class V33HdrPickTest {
    private static JSONObject v(String url, String vc, int h, double fps, String dr) throws Exception {
        JSONObject o = new JSONObject().put("url", url).put("protocol", "https").put("vcodec", vc).put("acodec", "none")
                .put("width", h * 16 / 9).put("height", h).put("fps", fps).put("ext", "webm");
        if (dr != null) o.put("dynamic_range", dr);
        return o;
    }

    @Test public void sdrBeatsHdrAtTheSameHeightWhateverTheOrder() throws Exception {
        JSONObject a = new JSONObject().put("url", "https://a").put("protocol", "https").put("vcodec", "none").put("acodec", "opus").put("abr", 128).put("ext", "webm");
        for (boolean hdrFirst : new boolean[]{true, false}) {
            JSONArray fs = new JSONArray();
            JSONObject hdr = v("https://hdr", "vp09.02.40.10.01.09.16.09.00", 1080, 30, "HDR10");
            JSONObject sdr = v("https://sdr", "vp09.00.40.08", 1080, 30, "SDR");
            if (hdrFirst) fs.put(hdr).put(sdr); else fs.put(sdr).put(hdr);
            fs.put(a);
            String[] r = YtEngine.pickStreams(new JSONObject().put("formats", fs), 1080);
            assertNotNull(r);
            assertEquals("https://sdr", r[0]);
        }
    }

    @Test public void hdrDetection() throws Exception {
        assertTrue(YtEngine.isHdr(v("u", "vp09.00.40.08", 1080, 30, "HLG")));
        assertTrue(YtEngine.isHdr(v("u", "vp09.02.40.10", 1080, 30, null)));
        assertTrue(YtEngine.isHdr(v("u", "av01.0.09M.10.0.110.09.16.09.0", 1080, 30, null)));
        assertFalse(YtEngine.isHdr(v("u", "avc1.640028", 1080, 30, "SDR")));
        assertFalse(YtEngine.isHdr(v("u", "av01.0.08M.08", 1080, 30, null)));
    }
}

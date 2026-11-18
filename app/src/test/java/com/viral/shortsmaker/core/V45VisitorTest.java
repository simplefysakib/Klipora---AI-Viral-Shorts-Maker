package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** v45 (5.7 build 45): the player call carries YouTube's visitor id when one is read; without it the call is unchanged. */
public class V45VisitorTest {

    @Test public void noVisitorLeavesTheCallAsItWas() {
        assertEquals("", YtEngine.visitorField(null));
        assertEquals("", YtEngine.visitorField(""));
    }

    @Test public void visitorIdGoesIntoTheInnerTubeContext() {
        String v = "Cgs0SE82d2pMREt2ayiD7MnXBjIK";
        String field = YtEngine.visitorField(v);
        assertEquals(",\"visitorData\":\"" + v + "\"", field);
        String payload = "{\"videoId\":\"jNQXAC9IVRw\",\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20250214.01.00\",\"hl\":\"en\"" + field + "}}}";
        assertTrue(payload.contains("\"hl\":\"en\",\"visitorData\":\"" + v + "\"}}}"));
    }

    @Test public void bareCallHasNoVisitorField() {
        String payload = "{\"context\":{\"client\":{\"hl\":\"en\"" + YtEngine.visitorField(null) + "}}}";
        assertFalse(payload.contains("visitorData"));
        assertEquals("{\"context\":{\"client\":{\"hl\":\"en\"}}}", payload);
    }
}

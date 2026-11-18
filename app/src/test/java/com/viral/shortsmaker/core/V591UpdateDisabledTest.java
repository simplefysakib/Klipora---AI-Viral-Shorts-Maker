package com.viral.shortsmaker.core;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class V591UpdateDisabledTest {
    @Test public void remoteVersionCheckIsPermanentlyOff() {
        assertFalse(UpdateCheck.enabled());
    }
}

package org.hurtorius.mirror.core;

/** Matches picture brightness for native text while preserving its opacity. */
public final class ScreenBrightness {
    public static int textColor(int alpha, double brightness) {
        int value=(int)Math.clamp(255*brightness,0,255);
        return (Math.clamp(alpha,0,255)<<24)|(value<<16)|(value<<8)|value;
    }
}

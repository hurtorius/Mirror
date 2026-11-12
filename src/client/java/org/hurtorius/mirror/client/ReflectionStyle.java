package org.hurtorius.mirror.client;
/** Packs image adjustments into otherwise unused vertex color channels for GPU-only pictures. */
public final class ReflectionStyle {
    private ReflectionStyle(){}
    public static int color(int alpha,double brightness,double contrast,double saturation){return (alpha<<24)|(pack(brightness)<<16)|(pack(contrast)<<8)|pack(saturation);}
    private static int pack(double value){return (int)Math.clamp(Math.round(value*128),0,255);}
}

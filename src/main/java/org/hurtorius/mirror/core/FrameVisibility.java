package org.hurtorius.mirror.core;
/** Private browser previews never become world pixels merely by enabling a screen. */
public final class FrameVisibility {
    private FrameVisibility(){}
    public static boolean publicPicture(ScreenSpec.Source source,String epoch){
        return source!=ScreenSpec.Source.WEB&&source!=ScreenSpec.Source.SHARE||epoch!=null&&!epoch.isBlank();
    }
}

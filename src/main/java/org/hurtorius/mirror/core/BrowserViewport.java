package org.hurtorius.mirror.core;

/** CSS viewport pixels. Automatic mode preserves the physical screen ratio at a bounded cost. */
public record BrowserViewport(int width,int height) {
    public static BrowserViewport of(ScreenSpec s){
        if(!s.browserAutoSize)return new BrowserViewport(s.browserWidth,s.browserHeight);
        double ratio=s.width/s.height;
        double w=Math.sqrt(921600*ratio),h=w/ratio;
        double scale=Math.min(1,Math.min(1920/w,1920/h));
        return new BrowserViewport(Math.max(16,(int)Math.round(w*scale)),Math.max(16,(int)Math.round(h*scale)));
    }
}

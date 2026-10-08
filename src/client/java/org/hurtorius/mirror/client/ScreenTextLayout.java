package org.hurtorius.mirror.client;



/** Detached native-font canvas fitted once into a whole wall, before a cell clips it. */
public record ScreenTextLayout(float centerX, float centerY, float scaleX, float scaleY) {
    public static final int WRAP_WIDTH = 320, MAX_LINES = 48, LINE_HEIGHT = 11;

    public static ScreenTextLayout of(PreviewConfig.Shape shape, float width, float height,
                                      float curve, float corner, PreviewConfig.Fit fit, String polygon,
                                      ScreenTileLayout.Tile tile, int widest, int lines) {
        // The padding preserves ordinary flat-screen text sizing. ORIGINAL still means
        // 128 native font pixels per block, and STRETCH intentionally changes glyph aspect.
        int canvasWidth = Math.max(1, (int) Math.ceil(Math.clamp(widest, 1, 32768) / .88));
        int canvasHeight = Math.max(20, (int) Math.ceil(Math.max(LINE_HEIGHT,
                Math.clamp(lines, 0, MAX_LINES) * LINE_HEIGHT) / .78));
        ScreenMesh.Bounds bounds = ScreenMesh.content(shape, width, height, curve, corner, fit,
                canvasWidth, canvasHeight, 0, tile, polygon).imageBounds();
        return new ScreenTextLayout((bounds.x0() + bounds.x1()) / 2, (bounds.y0() + bounds.y1()) / 2,
                bounds.width() / canvasWidth, bounds.height() / canvasHeight);
    }

    public float x(float pixelX) { return centerX + pixelX * scaleX; }
    public float y(float pixelY) { return centerY - pixelY * scaleY; }
    public float depth(float pixelDepth) { return pixelDepth * Math.min(scaleX, scaleY); }
}

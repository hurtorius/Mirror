package org.hurtorius.mirror.client;



/** Pure equal-cell video-wall layout. Columns run left to right; rows run top to bottom. */
public final class ScreenTileLayout {
    private ScreenTileLayout() { }

    /** A bounded, zero-based cell selection, also suitable as a geometry-cache key. */
    public record Tile(int columns, int rows, int column, int row) {
        public Tile {
            columns = Math.clamp(columns, 1, 8);
            rows = Math.clamp(rows, 1, 8);
            column = Math.clamp(column, 0, columns - 1);
            row = Math.clamp(row, 0, rows - 1);
        }
        public boolean tiled() { return columns != 1 || rows != 1; }
    }

    /**
     * Fits the image ONCE in the entire logical wall, then translates its bounds into
     * the selected cell's coordinates. Clipping those bounds to a cell or silhouette
     * does not change the texture scale. Empty cells legitimately remain background.
     * Width/height are the cell's unrolled dimensions, not the outline's inscribed box.
     */
    public static ScreenMesh.Bounds imageBounds(float width, float height, PreviewConfig.Fit fit,
                                                int pixelsW, int pixelsH, Tile tile) {
        width = dimension(width);
        height = dimension(height);
        float wallW = width * tile.columns, wallH = height * tile.rows;
        float centerX = (tile.column + .5f) * width - wallW / 2;
        float centerY = wallH / 2 - (tile.row + .5f) * height;
        if (pixelsW <= 0 || pixelsH <= 0)
            return new ScreenMesh.Bounds(-centerX, -centerY, -centerX, -centerY);
        float imageW, imageH;
        fit = fit == null ? PreviewConfig.Fit.FIT : fit;
        if (fit == PreviewConfig.Fit.STRETCH) {
            imageW = wallW; imageH = wallH;
        } else if (fit == PreviewConfig.Fit.ORIGINAL) {
            imageW = pixelsW / ScreenMesh.ORIGINAL_PIXELS_PER_BLOCK;
            imageH = pixelsH / ScreenMesh.ORIGINAL_PIXELS_PER_BLOCK;
        } else {
            float scale = fit == PreviewConfig.Fit.FILL ? Math.max(wallW / pixelsW, wallH / pixelsH)
                    : Math.min(wallW / pixelsW, wallH / pixelsH);
            imageW = pixelsW * scale; imageH = pixelsH * scale;
        }
        return new ScreenMesh.Bounds(-imageW / 2 - centerX, -imageH / 2 - centerY,
                imageW / 2 - centerX, imageH / 2 - centerY);
    }

    private static float dimension(float value) {
        return Float.isFinite(value) ? Math.clamp(value, .01f, 64) : .01f;
    }
}

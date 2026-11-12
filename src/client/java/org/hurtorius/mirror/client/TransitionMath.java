package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig.Transition;

/** Deterministic, bounded transition sampling. No textures, clocks, or authorization state are retained. */
public final class TransitionMath {
    public static final int DISSOLVE_COLUMNS = 12, DISSOLVE_ROWS = 8;
    public static final int DISSOLVE_CELLS = DISSOLVE_COLUMNS * DISSOLVE_ROWS;
    private TransitionMath() { }

    /** Offsets are fractions of the unrolled panel dimensions. Masks are applied before cylindrical mapping. */
    public record Plan(Transition effect, float progress, float scaleX, float scaleY, float offsetX, float alpha) {
        public boolean complete() { return progress >= 1; }
    }

    public static Plan content(Transition effect, float progress, boolean reduceMotion) {
        effect = effect == null ? Transition.FADE : effect;
        if (effect == Transition.RISE) effect = Transition.FADE;
        float p = reduceMotion || effect == Transition.NONE ? 1 : progress(progress);
        float scale = effect == Transition.ZOOM ? .65f + .35f * p : 1;
        return new Plan(effect, p, scale, effect == Transition.UNFOLD ? p : scale,
                effect == Transition.SLIDE ? 1 - p : 0,
                effect == Transition.FADE || effect == Transition.ZOOM ? p : 1);
    }

    public static float progress(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
    }

    /** A fixed permutation gives every cell a unique reveal threshold, independent of frame rate. */
    public static boolean cellVisible(int column, int row, float progress) {
        if (column < 0 || column >= DISSOLVE_COLUMNS || row < 0 || row >= DISSOLVE_ROWS) return false;
        int rank = ((row * DISSOLVE_COLUMNS + column) * 37 + 17) % DISSOLVE_CELLS;
        return progress(progress) * DISSOLVE_CELLS >= rank + 1;
    }
}

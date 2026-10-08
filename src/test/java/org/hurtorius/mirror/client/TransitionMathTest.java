package org.hurtorius.mirror.client;

import org.hurtorius.mirror.client.PreviewConfig.Transition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class TransitionMathTest {
    @ParameterizedTest @EnumSource(Transition.class)
    void reducedMotionAlwaysReturnsTheCompleteUntransformedLayer(Transition effect) {
        for (float progress : new float[] {-1, 0, .4f, 1, 2, Float.NaN, Float.POSITIVE_INFINITY}) {
            var plan = TransitionMath.appearance(effect, progress, true);
            assertTrue(plan.complete()); assertEquals(1, plan.scaleX()); assertEquals(1, plan.scaleY());
            assertEquals(1, plan.alpha()); assertEquals(0, plan.offsetX());
        }
    }
    @ParameterizedTest @EnumSource(Transition.class)
    void plansAreFiniteAndClampedAndReachExactEndpoints(Transition effect) {
        for (float progress : new float[] {-1, 0, .4f, 1, 2, Float.NaN, Float.POSITIVE_INFINITY}) {
            var plan = TransitionMath.content(effect, progress, false);
            assertTrue(plan.progress() >= 0 && plan.progress() <= 1);
            assertTrue(Float.isFinite(plan.scaleX()) && Float.isFinite(plan.scaleY()));
            assertTrue(plan.alpha() >= 0 && plan.alpha() <= 1);
        }
        var end = TransitionMath.content(effect, 1, false);
        assertEquals(1, end.scaleX()); assertEquals(1, end.scaleY()); assertEquals(0, end.offsetX()); assertEquals(1, end.alpha());
    }
    @Test void selectorsHaveDistinctEffectsAndNullsUseDocumentedDefaults() {
        assertEquals(Transition.UNFOLD, TransitionMath.appearance(null, .5f, false).effect());
        assertEquals(Transition.FADE, TransitionMath.content(null, .5f, false).effect());
        assertEquals(.5f, TransitionMath.content(Transition.SLIDE, .5f, false).offsetX());
        assertEquals(.825f, TransitionMath.content(Transition.ZOOM, .5f, false).scaleX(), .000001);
        assertEquals(.5f, TransitionMath.content(Transition.UNFOLD, .5f, false).scaleY());
        assertEquals(1, TransitionMath.content(Transition.UNFOLD, .5f, false).alpha());
        assertEquals(.5f, TransitionMath.appearance(Transition.UNFOLD, .5f, false).alpha());
        assertTrue(TransitionMath.content(Transition.IRIS, .5f, false).masked());
        assertTrue(TransitionMath.content(Transition.DISSOLVE, .5f, false).masked());
        assertTrue(TransitionMath.content(Transition.NONE, 0, false).complete());
    }
    @Test void dissolveIsDeterministicMonotonicAndRevealsEachCellExactlyOnce() {
        int[] revealed = new int[TransitionMath.DISSOLVE_CELLS];
        for (int step = 0; step <= TransitionMath.DISSOLVE_CELLS; step++) {
            float p = (step + .00001f) / TransitionMath.DISSOLVE_CELLS;
            int count = 0;
            for (int y = 0; y < TransitionMath.DISSOLVE_ROWS; y++) for (int x = 0; x < TransitionMath.DISSOLVE_COLUMNS; x++) {
                boolean visible = TransitionMath.cellVisible(x, y, p);
                int index = y * TransitionMath.DISSOLVE_COLUMNS + x;
                assertEquals(visible, TransitionMath.cellVisible(x, y, p));
                if (visible) { revealed[index]++; count++; }
                else assertEquals(0, revealed[index]);
            }
            assertEquals(step, count);
        }
        assertFalse(TransitionMath.cellVisible(-1, 0, 1));
        assertFalse(TransitionMath.cellVisible(0, TransitionMath.DISSOLVE_ROWS, 1));
        assertFalse(TransitionMath.cellVisible(0, 0, Float.NaN));
    }
    @Test void riseTravelsFromTheCrystalToTheActualWorldDestination() {
        assertEquals(.71875, TransitionMath.riseCoordinate(.71875, 8, 0, false));
        assertEquals(4.359375, TransitionMath.riseCoordinate(.71875, 8, .5f, false));
        assertEquals(-16, TransitionMath.riseCoordinate(.5, -16, 1, false));
        assertEquals(1000, TransitionMath.riseCoordinate(.5, 1000, 0, true));
        assertEquals(.5, TransitionMath.riseCoordinate(.5, 1000, Float.NaN, false));
        var plan = TransitionMath.appearance(Transition.RISE, .5f, false);
        assertEquals(.55f, plan.scaleX()); assertEquals(.55f, plan.scaleY()); assertEquals(.5f, plan.alpha());
        assertEquals(Transition.FADE, TransitionMath.content(Transition.RISE, .5f, false).effect());
    }
}

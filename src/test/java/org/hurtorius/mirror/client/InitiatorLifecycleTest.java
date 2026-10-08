package org.hurtorius.mirror.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitiatorLifecycleTest {
    @Test void chunkLoadIsSettledAndNeverPlacement() {
        var off = InitiatorLifecycle.loaded(false, 100);
        var active = InitiatorLifecycle.loaded(true, 100);
        assertEquals(InitiatorLifecycle.Phase.IDLE, off.phase(100));
        assertEquals(InitiatorLifecycle.Phase.IDLE, active.phase(100));
        assertEquals(1, off.sample(100, false).alpha());
        assertEquals(10f/16, off.sample(100, false).height());
        assertEquals(11.5f/16, active.sample(100, false).height());
        assertEquals(InitiatorLifecycle.Mood.DORMANT, off.sample(100, false).mood());
        assertEquals(InitiatorLifecycle.Mood.ACTIVE, active.sample(100, false).mood());
    }
    @Test void actualPlacementRisesSpinsPulsesAndSettlesDormant() {
        var lifecycle = InitiatorLifecycle.placed(100);
        var start = lifecycle.sample(100, false);
        var middle = lifecycle.sample(112, false);
        var end = lifecycle.sample(124, false);
        assertEquals(InitiatorLifecycle.Phase.PLACING, lifecycle.phase(100));
        assertEquals(0, start.alpha());
        assertTrue(middle.height() > start.height());
        assertTrue(middle.scale() > start.scale());
        assertTrue(middle.ringScale() > start.ringScale());
        assertTrue(middle.pulse() > 0);
        assertTrue(end.spin() > middle.spin());
        assertEquals(InitiatorLifecycle.Phase.IDLE, lifecycle.phase(124));
        assertEquals(1, end.alpha()); assertEquals(0, end.energy());
        assertEquals(InitiatorLifecycle.Mood.DORMANT, end.mood());
    }
    @Test void wakeAndDismissAreExplicitContinuousOppositeMotion() {
        var lifecycle = InitiatorLifecycle.loaded(false, 0);
        lifecycle.observe(true, 10);
        var start = lifecycle.sample(10, false);
        var middle = lifecycle.sample(20, false);
        assertEquals(InitiatorLifecycle.Phase.WAKING, lifecycle.phase(20));
        assertTrue(middle.height() > start.height());
        assertTrue(middle.energy() > start.energy());
        lifecycle.observe(false, 20);
        var reverseStart = lifecycle.sample(20, false);
        assertEquals(middle.height(), reverseStart.height(), 1e-6);
        assertEquals(middle.scale(), reverseStart.scale(), 1e-6);
        assertEquals(middle.spin(), reverseStart.spin(), 1e-6);
        var reverseMiddle = lifecycle.sample(28, false);
        assertEquals(InitiatorLifecycle.Phase.DISMISSING, lifecycle.phase(28));
        assertTrue(reverseMiddle.height() < reverseStart.height());
        assertTrue(reverseMiddle.energy() < reverseStart.energy());
        assertTrue(reverseMiddle.spin() < reverseStart.spin());
        assertEquals(0, lifecycle.sample(36, false).energy());
    }
    @Test void repeatedSameStateDoesNotRestartAnimation() {
        var lifecycle = InitiatorLifecycle.loaded(false, 0);
        lifecycle.observe(true, 1); lifecycle.observe(true, 12);
        assertEquals(InitiatorLifecycle.Phase.IDLE, lifecycle.phase(21));
        assertEquals(1, lifecycle.sample(21, false).energy());
    }
    @Test void breakCollapsesReversesFadesAndHasExactLifetime() {
        var start = InitiatorLifecycle.loaded(true, 0).sample(0, false);
        var broken = InitiatorLifecycle.broken(start, 10);
        var middle = broken.sample(18, false);
        assertTrue(middle.height() < start.height());
        assertTrue(middle.scale() < start.scale());
        assertTrue(middle.ringScale() < start.ringScale());
        assertTrue(middle.alpha() < start.alpha());
        assertTrue(middle.spin() < start.spin());
        assertFalse(broken.expired(25.99)); assertTrue(broken.expired(26));
        assertEquals(0, broken.sample(26, false).alpha());
        broken.observe(true, 27);
        assertEquals(0, broken.sample(30, false).alpha(), "Removed blocks never wake again");
    }
    @Test void reducedMotionEliminatesAllTravelSpinPulseAndBreakGhosts() {
        var lifecycle = InitiatorLifecycle.placed(0);
        var a = lifecycle.sample(0, true);
        var b = lifecycle.sample(12, true);
        assertEquals(a, b);
        assertEquals(0, a.spin()); assertEquals(0, a.pulse());
        lifecycle.observe(true, 13);
        assertEquals(lifecycle.sample(13, true), lifecycle.sample(1000, true));
        assertEquals(1, lifecycle.sample(13, true).energy());
        var broken = InitiatorLifecycle.broken(lifecycle.sample(13, true), 13);
        assertEquals(0, broken.sample(13, true).alpha());
    }
    @Test void ringsStageInOrderGroundRippleAndSoftMotesAreBounded() {
        var lifecycle = InitiatorLifecycle.placed(0);
        var early = lifecycle.sample(5, false).effects();
        assertTrue(early.firstRing() > 0); assertEquals(0, early.secondRing());
        var middle = lifecycle.sample(12, false).effects();
        assertTrue(middle.secondRing() > 0); assertTrue(middle.rippleRadius() > 0); assertTrue(middle.rippleAlpha() > 0);
        for (int tick=0; tick<1000; tick++) {
            var effects = lifecycle.sample(tick, false).effects();
            assertTrue(effects.firstRing() >= 0 && effects.firstRing() <= 1);
            assertTrue(effects.secondRing() >= 0 && effects.secondRing() <= 1);
            assertTrue(effects.rippleAlpha() >= 0 && effects.rippleAlpha() <= .21);
            assertTrue(effects.particleAlpha() >= 0 && effects.particleAlpha() <= .3);
        }
        assertEquals(0, lifecycle.sample(24, false).effects().rippleAlpha());
        assertEquals(InitiatorLifecycle.Effects.NONE, lifecycle.sample(12, true).effects());
    }
    @Test void publicMoodHintsChangeOnlyPaletteAndNeverEnableAnIdleScreen() {
        var pose = InitiatorLifecycle.loaded(false, 10).sample(10, true);
        for (var mood : InitiatorLifecycle.Mood.values()) {
            var hinted = pose.withMood(mood);
            assertEquals(mood, hinted.mood()); assertEquals(0, hinted.energy());
            assertEquals(pose.height(), hinted.height()); assertEquals(pose.effects(), hinted.effects());
        }
    }
    @Test void sampledMotionStaysFiniteAndBoundedAcrossLongIdleAndRapidInterruptions() {
        var lifecycle = InitiatorLifecycle.placed(0);
        for (int i = 0; i < 500; i++) {
            lifecycle.observe(i % 2 == 0, i);
            var pose = lifecycle.sample(i + .5, false);
            assertTrue(Float.isFinite(pose.height())); assertTrue(Float.isFinite(pose.spin()));
            assertTrue(pose.height() >= 4.5f/16 - .04 && pose.height() <= 11.5f/16 + .04);
            assertTrue(pose.alpha() >= 0 && pose.alpha() <= 1);
            assertTrue(pose.energy() >= 0 && pose.energy() <= 1);
        }
        assertTrue(Float.isFinite(lifecycle.sample(Double.NaN, false).height()));
    }
}

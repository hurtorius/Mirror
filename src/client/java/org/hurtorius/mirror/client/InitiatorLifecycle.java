package org.hurtorius.mirror.client;

/** Pure decorative choreography. No config, media, texture, audio or consent state. */
public final class InitiatorLifecycle {
    public static final double PLACE_TICKS = 24, WAKE_TICKS = 20, DISMISS_TICKS = 16, BREAK_TICKS = 16;
    public enum Phase { IDLE, PLACING, WAKING, DISMISSING, BREAKING }
    /** Optional moods come only from sanitized public hints and local public-frame readiness. */
    public enum Mood { DORMANT, PREPARING, ACTIVE, SHARING, LOCKED }
    public record Effects(float firstRing, float secondRing, float rippleRadius, float rippleAlpha,
                          float particlePhase, float particleAlpha) {
        public static final Effects NONE = new Effects(0, 0, 0, 0, 0, 0);
    }
    public record Pose(float height, float scale, float ringScale, float alpha,
                       float energy, float spin, float pulse, Mood mood, Effects effects) {
        public Pose(float height, float scale, float ringScale, float alpha, float energy, float spin, float pulse, Mood mood) {
            this(height, scale, ringScale, alpha, energy, spin, pulse, mood, Effects.NONE);
        }
        public Pose withMood(Mood next) { return new Pose(height, scale, ringScale, alpha, energy, spin, pulse, next, effects); }
    }
    private Phase phase = Phase.IDLE;
    private boolean active;
    private double started, duration;
    private Pose from, to;

    private InitiatorLifecycle(boolean active, double now) {
        this.active = active;
        started = time(now);
        from = to = rest(active, 0);
    }
    /** Loading/first observation starts settled, never as placement or wake. */
    public static InitiatorLifecycle loaded(boolean active, double now) { return new InitiatorLifecycle(active, now); }
    public static InitiatorLifecycle placed(double now) {
        InitiatorLifecycle result = loaded(false, now);
        result.from = new Pose(InitiatorVisualProfile.ORIGIN_Y, .05f, .04f, 0, 0, 0, 0, Mood.DORMANT);
        result.to = rest(false, (float) (Math.PI * 2));
        result.phase = Phase.PLACING;
        result.duration = PLACE_TICKS;
        return result;
    }
    public static InitiatorLifecycle broken(Pose pose, double now) {
        InitiatorLifecycle result = loaded(false, now);
        result.from = pose;
        result.to = new Pose(InitiatorVisualProfile.ORIGIN_Y, .02f, .02f, 0, 0, pose.spin() - (float) Math.PI, 0, Mood.DORMANT);
        result.phase = Phase.BREAKING;
        result.duration = BREAK_TICKS;
        return result;
    }
    public void observe(boolean enabled, double now) {
        if (phase == Phase.BREAKING || enabled == active) return;
        from = sample(now, false);
        active = enabled;
        started = time(now);
        duration = enabled ? WAKE_TICKS : DISMISS_TICKS;
        phase = enabled ? Phase.WAKING : Phase.DISMISSING;
        to = rest(enabled, from.spin() + (enabled ? (float) Math.PI : -(float) Math.PI));
    }
    public Phase phase(double now) { return elapsed(now) >= duration && phase != Phase.BREAKING ? Phase.IDLE : phase; }
    public boolean expired(double now) { return phase == Phase.BREAKING && elapsed(now) >= BREAK_TICKS; }
    public Pose sample(double now, boolean reducedMotion) {
        if (reducedMotion) {
            if (phase == Phase.BREAKING) return new Pose(InitiatorVisualProfile.ORIGIN_Y, 0, 0, 0, 0, 0, 0, Mood.DORMANT);
            return rest(active, 0);
        }
        double age = elapsed(now);
        float progress = duration <= 0 ? 1 : clamp((float) (age / duration));
        float ease = progress * progress * (3 - 2 * progress);
        Pose base = blend(from, to, ease);
        boolean moving = progress < 1;
        float pulse = moving ? (float) Math.sin(Math.PI * progress) * (phase == Phase.BREAKING ? .12f : .38f) : 0;
        float idleAge = (float) (Math.max(0, age - duration) % (Math.PI * 20000));
        float spin = base.spin() + (moving || phase == Phase.BREAKING ? 0 : idleAge * (active ? .018f : .008f));
        float bob = moving || phase == Phase.BREAKING ? 0 : (float) Math.sin(idleAge * .035f) * (active ? .025f : .008f);
        float first = 1, second = 1, radius = 0, ripple = 0;
        if (moving && (phase == Phase.PLACING || phase == Phase.WAKING)) {
            first = smooth(clamp((float) age / 8));
            second = smooth(clamp(((float) age - 6) / 10));
            float wave = clamp(((float) age - 8) / 12);
            radius = .28f + wave * 1.35f;
            ripple = (float) Math.sin(Math.PI * wave) * .2f;
        } else if (phase == Phase.BREAKING) {
            first = 1 - smooth(clamp(((float) age - 4) / 12));
            second = 1 - smooth(clamp((float) age / 10));
        }
        Effects effects = new Effects(first, second, radius, ripple, (float) (age % 160) / 160,
                base.alpha() * (phase == Phase.BREAKING ? 0 : .16f + base.energy() * .12f));
        return new Pose(base.height() + bob, base.scale(), base.ringScale(), base.alpha(), base.energy(), spin, pulse,
                active ? Mood.ACTIVE : Mood.DORMANT, effects);
    }
    private double elapsed(double now) { return Math.max(0, time(now) - started); }
    private static double time(double value) { return Double.isFinite(value) ? value : 0; }
    private static Pose rest(boolean active, float spin) {
        return new Pose(active ? InitiatorVisualProfile.ACTIVE_Y : InitiatorVisualProfile.IDLE_Y, active ? 1 : .82f, active ? 1 : .68f,
                1, active ? 1 : 0, spin, 0, active ? Mood.ACTIVE : Mood.DORMANT);
    }
    private static Pose blend(Pose a, Pose b, float t) {
        return new Pose(lerp(a.height(), b.height(), t), lerp(a.scale(), b.scale(), t),
                lerp(a.ringScale(), b.ringScale(), t), lerp(a.alpha(), b.alpha(), t),
                lerp(a.energy(), b.energy(), t), lerp(a.spin(), b.spin(), t), 0, b.mood());
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float smooth(float value) { return value * value * (3 - 2 * value); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}

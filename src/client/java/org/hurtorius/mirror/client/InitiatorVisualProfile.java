package org.hurtorius.mirror.client;

import java.util.List;

/** Approved compact-anchor artwork, converted from model pixels to block units. */
final class InitiatorVisualProfile {
    static final float ORIGIN_Y = 4.5f / 16;
    static final float IDLE_Y = 10f / 16;
    static final float ACTIVE_Y = 11.5f / 16;

    static final float CRYSTAL_RADIUS = 3.2f / 16;
    static final float CRYSTAL_TOP = 4.5f / 16;
    static final float CRYSTAL_BOTTOM = 3.75f / 16;
    static final String CRYSTAL_TEXTURE = "mirror:textures/block/initiator_crystal_facets.png";
    static final List<Uv> TOP_UV = List.of(new Uv(2.5f / 16, 0), new Uv(0, 5f / 16), new Uv(5f / 16, 5f / 16));
    static final List<Uv> BOTTOM_UV = List.of(new Uv(2.5f / 16, 13f / 16), new Uv(5f / 16, 8f / 16), new Uv(0, 8f / 16));
    static final int TOP_MIX_TO = 0xE9FCFF;
    static final int BOTTOM_MIX_TO = 0x163C65;
    static final List<Float> TOP_MIX_AMOUNTS = List.of(.05f, .35f, .55f, .15f);
    static final List<Float> BOTTOM_MIX_AMOUNTS = List.of(.35f, .5f, .25f, .65f);

    static final int SEGMENTS = 8;
    static final float GAP = .015f;
    static final List<Ring> RINGS = List.of(
            new Ring(5.15f / 16, 22.5f, 1f / 16, .65f / 16, 0, 0x97805B, 1f),
            new Ring(5.65f / 16, -67.5f, .75f / 16, .55f / 16, -1f / 16, 0x64B7B2, -.7f));

    record Uv(float u, float v) { }
    record Ring(float radius, float tiltDegrees, float width, float depth, float yOffset,
                int color, float spinMultiplier) { }

    private InitiatorVisualProfile() { }
}

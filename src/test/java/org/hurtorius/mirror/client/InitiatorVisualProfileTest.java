package org.hurtorius.mirror.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InitiatorVisualProfileTest {
    private static final double EPSILON = 1e-7;

    @Test void poseAndCrystalMatchAuthoritativePixelProfile() throws IOException {
        JsonObject profile = profile(), pose = profile.getAsJsonObject("pose"), crystal = profile.getAsJsonObject("crystal");
        assertEquals(pixels(pose, "origin"), InitiatorVisualProfile.ORIGIN_Y, EPSILON);
        assertEquals(pixels(pose, "idle"), InitiatorVisualProfile.IDLE_Y, EPSILON);
        assertEquals(pixels(pose, "active"), InitiatorVisualProfile.ACTIVE_Y, EPSILON);
        assertEquals(pixels(crystal, "radius"), InitiatorVisualProfile.CRYSTAL_RADIUS, EPSILON);
        assertEquals(pixels(crystal, "top"), InitiatorVisualProfile.CRYSTAL_TOP, EPSILON);
        assertEquals(pixels(crystal, "bottom"), InitiatorVisualProfile.CRYSTAL_BOTTOM, EPSILON);
        assertEquals(crystal.get("texture").getAsString(), InitiatorVisualProfile.CRYSTAL_TEXTURE);
        assertEquals(16, crystal.getAsJsonArray("texture_size").get(0).getAsInt());
        assertEquals(16, crystal.getAsJsonArray("texture_size").get(1).getAsInt());
        assertUv(crystal.getAsJsonArray("top_uv"), InitiatorVisualProfile.TOP_UV);
        assertUv(crystal.getAsJsonArray("bottom_uv"), InitiatorVisualProfile.BOTTOM_UV);
        assertEquals(Integer.parseInt(crystal.get("top_mix_to").getAsString(), 16), InitiatorVisualProfile.TOP_MIX_TO);
        assertEquals(Integer.parseInt(crystal.get("bottom_mix_to").getAsString(), 16), InitiatorVisualProfile.BOTTOM_MIX_TO);
        assertAmounts(crystal.getAsJsonArray("top_mix_amounts"), InitiatorVisualProfile.TOP_MIX_AMOUNTS);
        assertAmounts(crystal.getAsJsonArray("bottom_mix_amounts"), InitiatorVisualProfile.BOTTOM_MIX_AMOUNTS);
    }

    @Test void everyRingPropertyMatchesAuthoritativePixelProfile() throws IOException {
        JsonObject profile = profile();
        JsonArray rings = profile.getAsJsonArray("rings");
        assertEquals(rings.size(), InitiatorVisualProfile.RINGS.size());
        assertEquals(profile.get("ring_segments").getAsInt(), InitiatorVisualProfile.SEGMENTS);
        assertEquals(profile.get("ring_joint_gap_radians").getAsDouble(), InitiatorVisualProfile.GAP, EPSILON);
        for (int index = 0; index < rings.size(); index++) {
            JsonObject source = rings.get(index).getAsJsonObject();
            var ring = InitiatorVisualProfile.RINGS.get(index);
            assertEquals(pixels(source, "radius"), ring.radius(), EPSILON);
            assertEquals(source.get("tilt_x_degrees").getAsDouble(), ring.tiltDegrees(), EPSILON);
            assertEquals(pixels(source, "width"), ring.width(), EPSILON);
            assertEquals(pixels(source, "depth"), ring.depth(), EPSILON);
            assertEquals(pixels(source, "y_offset"), ring.yOffset(), EPSILON);
            assertEquals(Integer.parseInt(source.get("color").getAsString(), 16), ring.color());
            assertEquals(source.get("spin_multiplier").getAsDouble(), ring.spinMultiplier(), EPSILON);
        }
    }

    @Test void authoredCollectionsAreImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> InitiatorVisualProfile.RINGS.clear());
        assertThrows(UnsupportedOperationException.class, () -> InitiatorVisualProfile.TOP_UV.clear());
        assertThrows(UnsupportedOperationException.class, () -> InitiatorVisualProfile.BOTTOM_UV.clear());
        assertThrows(UnsupportedOperationException.class, () -> InitiatorVisualProfile.TOP_MIX_AMOUNTS.clear());
        assertThrows(UnsupportedOperationException.class, () -> InitiatorVisualProfile.BOTTOM_MIX_AMOUNTS.clear());
    }

    private static JsonObject profile() throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("art/initiator/runtime-profile.json"))).getAsJsonObject();
    }

    private static double pixels(JsonObject object, String field) { return object.get(field).getAsDouble() / 16; }

    private static void assertUv(JsonArray source, List<InitiatorVisualProfile.Uv> actual) {
        assertEquals(source.size(), actual.size());
        for (int index = 0; index < source.size(); index++) {
            assertEquals(source.get(index).getAsJsonArray().get(0).getAsDouble() / 16, actual.get(index).u(), EPSILON);
            assertEquals(source.get(index).getAsJsonArray().get(1).getAsDouble() / 16, actual.get(index).v(), EPSILON);
        }
    }

    private static void assertAmounts(JsonArray source, List<Float> actual) {
        assertEquals(source.size(), actual.size());
        for (int index = 0; index < source.size(); index++) assertEquals(source.get(index).getAsDouble(), actual.get(index), EPSILON);
    }
}

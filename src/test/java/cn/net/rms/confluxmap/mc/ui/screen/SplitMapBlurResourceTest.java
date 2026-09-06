package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SplitMapBlurResourceTest {
    private static final String RESOURCE =
        "/assets/confluxmap/shaders/post/split_map_blur.json";

    @Test
    void legacyEffectMatchesVanillaDefaultPasses() throws IOException {
        try (InputStream input = SplitMapBlurResourceTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, RESOURCE);
            final JsonObject effect = new JsonParser().parse(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            final JsonArray passes = effect.getAsJsonArray("passes");
            final float[] radii = {5f, 5f, 3f, 3f, 1f, 1f};

            assertEquals(radii.length, passes.size());
            for (int index = 0; index < passes.size(); index++) {
                final JsonObject pass = passes.get(index).getAsJsonObject();
                assertEquals("blur", pass.get("name").getAsString());
                assertEquals(index % 2 == 0 ? "minecraft:main" : "swap",
                    pass.get("intarget").getAsString());
                assertEquals(index % 2 == 0 ? "swap" : "minecraft:main",
                    pass.get("outtarget").getAsString());
                assertEquals(index % 2 == 0 ? 1f : 0f,
                    uniform(pass, "BlurDir").get(0).getAsFloat());
                assertEquals(index % 2 == 0 ? 0f : 1f,
                    uniform(pass, "BlurDir").get(1).getAsFloat());
                assertEquals(radii[index], uniform(pass, "Radius").get(0).getAsFloat());
            }
        }
    }

    private static JsonArray uniform(final JsonObject pass, final String name) {
        for (final JsonElement element : pass.getAsJsonArray("uniforms")) {
            final JsonObject uniform = element.getAsJsonObject();
            if (name.equals(uniform.get("name").getAsString())) {
                return uniform.getAsJsonArray("values");
            }
        }
        throw new AssertionError("Missing uniform " + name);
    }
}

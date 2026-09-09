package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.radar.HeadPartSelector;
import cn.net.rms.confluxmap.core.radar.PortraitLayout;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;

/** Extracts textured quads for only the face-like portion of a neutralized vanilla entity model. */
final class EntityHeadGeometry {
    private static final int CELL_PX = 32;
    /** Tight crops provide their own sampling bounds, so the dominant subject may use the cell. */
    private static final int CONTENT_PAD = 0;
    /** Keeps equally dominant cuboids together for multi-part subjects such as wither heads. */
    private static final float DOMINANT_SCORE_RATIO = 0.99f;

    private record RawVertex(float x, float y, float z, float u, float v) {
    }

    private record RawQuad(List<RawVertex> vertices, float depth) {
    }

    private record Bounds(
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ
    ) {
        float width() {
            return maxX - minX;
        }

        float height() {
            return maxY - minY;
        }

        float depth() {
            return maxZ - minZ;
        }

        float centerY() {
            return (minY + maxY) / 2f;
        }

        float dominance() {
            // The two thinnest dimensions describe a cuboid's compact cross-section. A long horn
            // therefore stays subordinate, and a zero-depth tendril plane cannot tie a solid head.
            final float[] dimensions = {width(), height(), depth()};
            java.util.Arrays.sort(dimensions);
            return dimensions[0] * dimensions[1];
        }

        float projectedDominance() {
            final float thickness = Math.min(width(), height());
            return thickness * thickness;
        }
    }

    private record RawCuboid(List<RawQuad> quads, Bounds bounds) {
    }

    private EntityHeadGeometry() {
    }

    static float[] project(final EntityModel<?> model, final String entityType, final int cellX, final int cellY) {
        return project(selectParts(model, entityType), entityType, cellX, cellY);
    }

    static float[] projectNeutral(
        final EntityModel<?> model,
        final String entityType,
        final int cellX,
        final int cellY
    ) {
        final List<ModelPart> modelParts = model.allParts();
        final List<PartPose> liveTransforms = modelParts.stream()
            .map(ModelPart::storePose)
            .toList();
        model.resetPose();
        try {
            return project(model, entityType, cellX, cellY);
        } finally {
            for (int i = 0; i < modelParts.size(); i++) {
                modelParts.get(i).loadPose(liveTransforms.get(i));
            }
        }
    }

    /** Projects an already-selected part group; separated so tests can drive raw model trees. */
    static float[] project(
        final List<ModelPart> parts,
        final String entityType,
        final int cellX,
        final int cellY
    ) {
        if (parts.isEmpty()) {
            return new float[0];
        }
        final List<RawCuboid> cuboids = new ArrayList<>();
        final PortraitLayout.Profile profile = PortraitLayout.profile(entityType);
        final double yawRadians = Math.toRadians(profile.yawDegrees());
        final float yawCos = (float) Math.cos(yawRadians);
        final float yawSin = (float) Math.sin(yawRadians);
        final double pitchRadians = Math.toRadians(profile.pitchDegrees());
        final float pitchCos = (float) Math.cos(pitchRadians);
        final float pitchSin = (float) Math.sin(pitchRadians);
        final boolean resetPartRotation = profile.resetPartRotation();
        for (final ModelPart part : parts) {
            final PoseStack matrices = new PoseStack();
            final float partPitch = part.xRot;
            final float partYaw = part.yRot;
            final float partRoll = part.zRot;
            // VoxelMap resets the selected head-group rotation before applying the portrait pose
            // globally. Keep child transforms intact: the muzzle and ears live below this part.
            part.setRotation(
                resetPartRotation ? 0f : partPitch,
                resetPartRotation ? 0f : partYaw,
                resetPartRotation ? 0f : partRoll
            );
            try {
                part.visit(matrices, (entry, path, index, cuboid) -> {
                    final RawCuboid converted = readCuboid(
                        entry, cuboid, pitchCos, pitchSin, yawCos, yawSin
                    );
                    if (converted != null) {
                        cuboids.add(converted);
                    }
                });
            } finally {
                // Entity models are renderer-owned singletons; do not leak the portrait pose back
                // into the next world render or another entity that shares this model.
                part.setRotation(partPitch, partYaw, partRoll);
            }
        }
        if (cuboids.isEmpty()) {
            return new float[0];
        }

        float largestDominance = 0f;
        for (final RawCuboid cuboid : cuboids) {
            largestDominance = Math.max(largestDominance, cuboid.bounds().dominance());
        }
        final boolean planarSubject = !(largestDominance > 0f);
        if (planarSubject) {
            for (final RawCuboid cuboid : cuboids) {
                largestDominance = Math.max(
                    largestDominance, cuboid.bounds().projectedDominance()
                );
            }
        }
        if (!(largestDominance > 0f)) {
            return new float[0];
        }
        final PortraitLayout.Framing framing = profile.framing();
        float dominantBottom = Float.NEGATIVE_INFINITY;
        for (final RawCuboid cuboid : cuboids) {
            final float dominance = planarSubject
                ? cuboid.bounds().projectedDominance()
                : cuboid.bounds().dominance();
            if (dominance >= largestDominance * DOMINANT_SCORE_RATIO) {
                dominantBottom = Math.max(dominantBottom, cuboid.bounds().maxY());
            }
        }
        final List<RawCuboid> subjectCuboids = new ArrayList<>();
        float subjectMinX = Float.POSITIVE_INFINITY;
        float subjectMinY = Float.POSITIVE_INFINITY;
        float subjectMaxX = Float.NEGATIVE_INFINITY;
        float subjectMaxY = Float.NEGATIVE_INFINITY;
        for (final RawCuboid cuboid : cuboids) {
            final float dominance = planarSubject
                ? cuboid.bounds().projectedDominance()
                : cuboid.bounds().dominance();
            final boolean dominant = dominance >= largestDominance * DOMINANT_SCORE_RATIO;
            final boolean included = switch (framing) {
                case COMPLETE -> true;
                case UPPER_SILHOUETTE -> dominant || cuboid.bounds().centerY() < dominantBottom;
                case DOMINANT -> dominant;
            };
            if (!included) {
                continue;
            }
            subjectCuboids.add(cuboid);
            subjectMinX = Math.min(subjectMinX, cuboid.bounds().minX());
            subjectMinY = Math.min(subjectMinY, cuboid.bounds().minY());
            subjectMaxX = Math.max(subjectMaxX, cuboid.bounds().maxX());
            subjectMaxY = Math.max(subjectMaxY, cuboid.bounds().maxY());
        }
        final float width = subjectMaxX - subjectMinX;
        final float height = subjectMaxY - subjectMinY;
        if (!(width > 0f) || !(height > 0f)) {
            return new float[0];
        }
        final List<RawCuboid> drawnCuboids = framing == PortraitLayout.Framing.UPPER_SILHOUETTE
            ? subjectCuboids
            : cuboids;
        final List<RawQuad> quads = drawnCuboids.stream().flatMap(cuboid -> cuboid.quads().stream())
            .sorted(Comparator.comparingDouble(RawQuad::depth).reversed())
            .toList();
        final PortraitLayout.Fit fit = PortraitLayout.fit(width, height, CELL_PX, CONTENT_PAD);
        final float offsetX = cellX + fit.left();
        final float offsetY = cellY + fit.top();

        final float[] projected = new float[quads.size() * 20];
        int out = 0;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                projected[out++] = offsetX + (vertex.x() - subjectMinX) * fit.scale();
                projected[out++] = offsetY + (vertex.y() - subjectMinY) * fit.scale();
                projected[out++] = 0f;
                projected[out++] = vertex.u();
                projected[out++] = vertex.v();
            }
        }
        return projected;
    }

    /**
     * Resolves the parts a portrait draws. The named part tree is the primary source on every
     * version, because part names come from model data and survive remapping; the reflective
     * strategies below it only cover pre-1.21.3 models that never retain their root part.
     */
    static List<ModelPart> selectParts(final EntityModel<?> model, final String entityType) {
        if (HeadPartSelector.usesFullModel(entityType)) {
            return fullModelParts(model);
        }
        final ModelPart root = rootPart(model);
        if (root != null) {
            final List<ModelPart> selected = selectFromRoot(root, entityType);
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        return List.of();
    }

    /** Path-based selection over a named part tree, shared by every version and by the tests. */
    static List<ModelPart> selectFromRoot(final ModelPart root, final String entityType) {
        final Map<String, ModelPart> byPath = new LinkedHashMap<>();
        collectPaths(root, "root", byPath, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        final Set<String> selectedPaths = HeadPartSelector.select(entityType, byPath.keySet());
        final List<ModelPart> selected = new ArrayList<>();
        for (final String path : selectedPaths) {
            boolean coveredByAncestor = false;
            for (final String other : selectedPaths) {
                if (!path.equals(other) && path.startsWith(other + "/")) {
                    coveredByAncestor = true;
                    break;
                }
            }
            if (!coveredByAncestor) {
                selected.add(byPath.get(path));
            }
        }
        return selected;
    }

    private static ModelPart rootPart(final EntityModel<?> model) {
        return model.root();
    }

    private static List<ModelPart> fullModelParts(final EntityModel<?> model) {
        return List.of(model.root());
    }

    private static void collectPaths(
        final ModelPart part,
        final String path,
        final Map<String, ModelPart> result,
        final Set<ModelPart> visited
    ) {
        if (!visited.add(part)) {
            return;
        }
        result.put(path, part);
        for (final Map.Entry<String, ModelPart> child : children(part).entrySet()) {
            collectPaths(child.getValue(), path + "/" + child.getKey(), result, visited);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, ModelPart> children(final ModelPart part) {
        for (final Field field : part.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                final Object value = field.get(part);
                if (value instanceof Map) {
                    final Map<?, ?> map = (Map<?, ?>) value;
                    if (map.isEmpty() || map.values().iterator().next() instanceof ModelPart) {
                        return (Map<String, ModelPart>) map;
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // Try the next map field; an inaccessible field is not fatal to icon fallback.
            }
        }
        return Map.of();
    }

    private static List<ModelPart> smallestPartGroup(final EntityModel<?> model) {
        List<ModelPart> smallest = List.of();
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || !Iterable.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    final List<ModelPart> parts = iterableParts(method.invoke(model));
                    if (!parts.isEmpty() && (smallest.isEmpty() || parts.size() < smallest.size())) {
                        smallest = parts;
                    }
                } catch (final ReflectiveOperationException | RuntimeException ignored) {
                    // A model without an accessible head group falls back to all top-level parts.
                }
            }
        }
        return smallest;
    }

    private static List<ModelPart> iterableParts(final Object value) {
        if (!(value instanceof Iterable<?>)) {
            return List.of();
        }
        final List<ModelPart> parts = new ArrayList<>();
        for (final Object item : (Iterable<?>) value) {
            if (item instanceof ModelPart) {
                parts.add((ModelPart) item);
            }
        }
        return parts;
    }

    private static List<ModelPart> topLevelParts(final EntityModel<?> model) {
        final LinkedHashSet<ModelPart> found = new LinkedHashSet<>();
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !ModelPart.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final Object value = field.get(model);
                    if (value instanceof ModelPart) {
                        found.add((ModelPart) value);
                    }
                } catch (final ReflectiveOperationException | RuntimeException ignored) {
                    // Keep collecting other accessible model parts.
                }
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        final Set<ModelPart> children = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final ModelPart part : found) {
            children.addAll(children(part).values());
        }
        found.removeAll(children);
        return List.copyOf(found);
    }

    private static RawCuboid readCuboid(
        final PoseStack.Pose entry,
        final ModelPart.Cube cuboid,
        final float pitchCos,
        final float pitchSin,
        final float yawCos,
        final float yawSin
    ) {
        final Object sides = firstArrayField(cuboid);
        if (sides == null) {
            return null;
        }
        final List<RawQuad> quads = new ArrayList<>();
        for (int side = 0; side < Array.getLength(sides); side++) {
            final Object quad = Array.get(sides, side);
            final Object vertices = firstArrayField(quad);
            if (vertices == null || Array.getLength(vertices) != 4) {
                continue;
            }
            final List<RawVertex> converted = new ArrayList<>(4);
            float depth = 0f;
            for (int i = 0; i < 4; i++) {
                final Object vertex = Array.get(vertices, i);
                final RawVertex raw = readVertex(
                    entry, vertex, pitchCos, pitchSin, yawCos, yawSin
                );
                if (raw == null) {
                    converted.clear();
                    break;
                }
                converted.add(raw);
                depth += raw.z();
            }
            if (converted.size() == 4) {
                quads.add(new RawQuad(List.copyOf(converted), depth / 4f));
            }
        }
        if (quads.isEmpty()) {
            return null;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (final RawQuad quad : quads) {
            for (final RawVertex vertex : quad.vertices()) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
                maxZ = Math.max(maxZ, vertex.z());
            }
        }
        return new RawCuboid(List.copyOf(quads), new Bounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static RawVertex readVertex(
        final PoseStack.Pose entry,
        final Object vertex,
        final float pitchCos,
        final float pitchSin,
        final float yawCos,
        final float yawSin
    ) {
        if (!(vertex instanceof ModelPart.Vertex)) {
            return null;
        }
        final ModelPart.Vertex direct = (ModelPart.Vertex) vertex;
        final Vector3f transformed = new Vector3f(
            direct.worldX(), direct.worldY(), direct.worldZ()
        ).mulPosition(entry.pose());
        final float y = transformed.y * pitchCos - transformed.z * pitchSin;
        final float pitchedZ = transformed.y * pitchSin + transformed.z * pitchCos;
        final float x = transformed.x * yawCos + pitchedZ * yawSin;
        final float z = -transformed.x * yawSin + pitchedZ * yawCos;
        return new RawVertex(x, y, z, direct.u(), direct.v());
    }

    private static Object firstArrayField(final Object owner) {
        if (owner == null) {
            return null;
        }
        for (final Field field : owner.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // Try another array field.
            }
        }
        return null;
    }
}

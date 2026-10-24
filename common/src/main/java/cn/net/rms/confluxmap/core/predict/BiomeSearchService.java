package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.store.ColumnStore;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Seed-backed biome candidate sampling shared by the fullscreen search UI. */
public final class BiomeSearchService {
    private static final int MAX_GRID_SIDE = 513;

    /**
     * Side of the per-candidate surface refinement grid. One scale-16 biome cell spans 16
     * blocks, so a 9x9 grid at a 4-block stride covers the coarse cell plus one neighbour in
     * every direction and pins the candidate to 4-block (quart) accuracy.
     */
    private static final int REFINE_SIDE = 9;
    private static final int REFINE_STRIDE = 4;
    private static final int REFINE_HALF_SPAN = 16;

    private BiomeSearchService() {
    }

    public static List<BiomeCandidateSearch.Candidate> search(
        final ColumnStore store,
        final String targetBiomeId,
        final OptionalInt predictedBiomeId,
        final BaselineSampler sampler,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit
    ) {
        return search(
            store, targetBiomeId, predictedBiomeId, sampler,
            centerX, centerZ, radius, limit, SearchOrder.NEAREST
        );
    }

    public static List<BiomeCandidateSearch.Candidate> search(
        final ColumnStore store,
        final String targetBiomeId,
        final OptionalInt predictedBiomeId,
        final BaselineSampler sampler,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order
    ) {
        final List<BiomeCandidateSearch.Candidate> candidates = new ArrayList<>(
            BiomeCandidateSearch.findExplored(
                store, targetBiomeId, centerX, centerZ, radius, limit, order
            )
        );
        if ((candidates.size() < limit || order == SearchOrder.FARTHEST)
            && predictedBiomeId.isPresent() && sampler != null) {
            for (final BiomeCandidateSearch.Candidate candidate : samplePredicted(
                sampler,
                predictedBiomeId.getAsInt(),
                centerX,
                centerZ,
                radius,
                limit,
                order
            )) {
                if (!store.hasRealChunk(
                    Math.floorDiv(candidate.blockX(), 16),
                    Math.floorDiv(candidate.blockZ(), 16)
                )) {
                    candidates.add(candidate);
                }
            }
        }
        return BiomeCandidateSearch.select(
            centerX, centerZ, radius, limit, candidates, order
        );
    }

    static List<BiomeCandidateSearch.Candidate> samplePredicted(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit
    ) {
        return samplePredicted(sampler, targetBiomeId, centerX, centerZ, radius, limit, SearchOrder.NEAREST);
    }

    static List<BiomeCandidateSearch.Candidate> samplePredicted(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order
    ) {
        final int radiusInBlocks = Math.max(4, ((radius + 3) / 4) * 4);
        final int spanInBlocks = radiusInBlocks * 2;
        final int minimumStride = (spanInBlocks + MAX_GRID_SIDE - 2) / (MAX_GRID_SIDE - 1);
        final int strideInBlocks = Math.max(4, ((minimumStride + 3) / 4) * 4);
        final int originX = Math.floorDiv(centerX, 4) * 4 - radiusInBlocks;
        final int originZ = Math.floorDiv(centerZ, 4) * 4 - radiusInBlocks;
        final int side = spanInBlocks / strideInBlocks + 1;

        // Sparse coarse pass first: one biome-source query per cell costs about a third of
        // the full surface solve the old path pays per cell. Scale 16 (Overworld climate, End)
        // hits are refined back to surface-biome agreement below; scale 4 (Nether, End source
        // grid) matches the dense fallback's semantics and returns directly. A miss or an
        // unavailable sparse path falls through to the surface pass - the zero-miss guarantee.
        final int[] biomeIds = new int[side * side];

        // Scale 16: stride rounded up so the coarse grid covers at least the search span.
        final int stride16 = Math.max(1, (strideInBlocks + 15) / 16);
        final int originX16 = Math.floorDiv(originX, 16);
        final int originZ16 = Math.floorDiv(originZ, 16);
        final List<BiomeCandidateSearch.Candidate> coarse16 = sampler.biomesSparseStrided(
            16, originX16, originZ16, side, side, stride16, biomeIds
        ) ? BiomeCandidateSearch.fromGrid(
            targetBiomeId,
            originX16 * 16,
            originZ16 * 16,
            side,
            side,
            stride16 * 16,
            biomeIds,
            order
        ) : List.of();
        if (!coarse16.isEmpty()) {
            // Refine only the winners: a 100k-radius coarse grid can hold hundreds of
            // connected components, and each refinement is a small but non-free surface grid.
            final List<BiomeCandidateSearch.Candidate> winners = BiomeCandidateSearch.select(
                centerX, centerZ, radius, limit, coarse16, order
            );
            return BiomeCandidateSearch.select(
                centerX, centerZ, radius, limit,
                refineCoarseCandidates(sampler, targetBiomeId, winners),
                order
            );
        }

        // Scale 4: exact quart stride (strideInBlocks is a multiple of 4).
        final List<BiomeCandidateSearch.Candidate> coarse4 = sampler.biomesSparseStrided(
            4, Math.floorDiv(originX, 4), Math.floorDiv(originZ, 4), side, side, strideInBlocks / 4, biomeIds
        ) ? BiomeCandidateSearch.fromGrid(
            targetBiomeId, originX, originZ, side, side, strideInBlocks, biomeIds, order
        ) : List.of();
        if (!coarse4.isEmpty()) {
            return BiomeCandidateSearch.select(centerX, centerZ, radius, limit, coarse4, order);
        }

        return surfaceScan(
            sampler, targetBiomeId, originX, originZ, side, strideInBlocks, centerX, centerZ, radius, limit, order
        );
    }

    /**
     * The coarse climate grid can disagree with surface biomes near elevation changes; each
     * candidate gets one small surface-grid lookup around its coarse hit, and keeps the coarse
     * coordinates when the refinement cannot confirm the target (matching the coarse-grid
     * accuracy the dense fallback already delivers).
     */
    private static List<BiomeCandidateSearch.Candidate> refineCoarseCandidates(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final List<BiomeCandidateSearch.Candidate> coarse
    ) {
        final List<BiomeCandidateSearch.Candidate> refined = new ArrayList<>(coarse.size());
        for (final BiomeCandidateSearch.Candidate candidate : coarse) {
            final int originX = candidate.blockX() - REFINE_HALF_SPAN;
            final int originZ = candidate.blockZ() - REFINE_HALF_SPAN;
            final int[] terrainY = new int[REFINE_SIDE * REFINE_SIDE];
            final int[] biomeIds = new int[REFINE_SIDE * REFINE_SIDE];
            if (sampler.overviewHeights(
                originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, terrainY
            ) && sampler.surfaceBiomes(
                originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, terrainY, biomeIds
            )) {
                final List<BiomeCandidateSearch.Candidate> hits = BiomeCandidateSearch.fromGrid(
                    targetBiomeId, originX, originZ, REFINE_SIDE, REFINE_SIDE, REFINE_STRIDE, biomeIds,
                    SearchOrder.NEAREST
                );
                if (!hits.isEmpty()) {
                    refined.add(hits.get(0));
                    continue;
                }
            }
            refined.add(candidate);
        }
        return refined;
    }

    private static List<BiomeCandidateSearch.Candidate> surfaceScan(
        final BaselineSampler sampler,
        final int targetBiomeId,
        final int originX,
        final int originZ,
        final int side,
        final int strideInBlocks,
        final int centerX,
        final int centerZ,
        final int radius,
        final int limit,
        final SearchOrder order
    ) {
        final int[] biomeIds = new int[side * side];
        final int[] terrainY = new int[side * side];
        final boolean sampledSurface = sampler.overviewHeights(
            originX, originZ, side, side, strideInBlocks, terrainY
        ) && sampler.surfaceBiomes(
            originX, originZ, side, side, strideInBlocks, terrainY, biomeIds
        );
        if (!sampledSurface && !sampler.biomesStrided(
            4,
            Math.floorDiv(originX, 4),
            Math.floorDiv(originZ, 4),
            side,
            side,
            strideInBlocks / 4,
            biomeIds
        )) {
            return List.of();
        }
        return BiomeCandidateSearch.select(
            centerX,
            centerZ,
            radius,
            limit,
            BiomeCandidateSearch.fromGrid(
                targetBiomeId,
                originX,
                originZ,
                side,
                side,
                strideInBlocks,
                biomeIds,
                order
            ),
            order
        );
    }
}

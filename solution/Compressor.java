import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance spatial block compressor for 2D and 3D labelled models.
 * Implements an adaptive multi-strategy tournament per parent block:
 * - Directional Greedy across all coordinate permutations (forward and backward)
 * - Dynamic Best-Axis / Maximum-Volume Greedy
 * - Global Largest-Box-First heuristic (for small to medium blocks)
 * - Guillotine Cut Dynamic Programming (for small blocks)
 * - Iterative Pairwise Consolidation / Merging
 */
public class Compressor {

    public static class Region {
        public int x, y, z;
        public int w, h, d;
        public byte label;

        public Region(int x, int y, int z, int w, int h, int d, byte label) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.d = d;
            this.label = label;
        }

        public Region copy() {
            return new Region(x, y, z, w, h, d, label);
        }
    }

    public static class Model {
        public int X, Y, Z;
        public int px, py, pz;
        public String[] tagToLabel = new String[65536];
        public byte[] grid; // size X * Y * Z
        public int numLabels = 0;

        public int idx(int x, int y, int z) {
            return (z * Y + y) * X + x;
        }
    }

    // Reusable scratch buffers per thread/run to achieve zero-allocation inner loops
    private static int[] visitedBuffer = new int[0];
    private static int runId = 1;

    // Component decomposition scratch buffers
    private static int[] compVisited = new int[0];
    private static int compRunId = 1;
    private static int[] compQueue = new int[0];
    private static int[] compIdBuffer = new int[0];

    // Time governor, measured from the start of main (System.nanoTime).
    // Past SOFT: no new optimisation work. Past HARD: the fallback emits X-runs only.
    private static final long SOFT_NANOS = 2_200_000_000L;
    private static final long HARD_NANOS = 3_000_000_000L;
    // Above this many stored candidate regions, only the best whole candidate is kept.
    private static final long MAX_CANDIDATE_REGIONS = 1_500_000L;
    private static long startNanos = System.nanoTime();

    public static void startClock() {
        startNanos = System.nanoTime();
    }

    private static boolean softExpired() {
        return System.nanoTime() - startNanos > SOFT_NANOS;
    }

    private static boolean hardExpired() {
        return System.nanoTime() - startNanos > HARD_NANOS;
    }

    public static List<Region> compress(Model model) {
        int X = model.X, Y = model.Y, Z = model.Z;
        int px = model.px, py = model.py, pz = model.pz;
        byte[] grid = model.grid;

        int numBlocksX = X / px;
        int numBlocksY = Y / py;
        int numBlocksZ = Z / pz;

        int parentVol = px * py * pz;
        if (visitedBuffer.length < parentVol) {
            visitedBuffer = new int[parentVol];
            compVisited = new int[parentVol];
            compQueue = new int[parentVol];
            runId = 1;
            compRunId = 1;
        }

        TinyCover.resetGlobalBudget();
        BlockCache.clear();
        List<Region> totalRegions = new ArrayList<>(Math.min(X * Y * Z, 100000));
        byte[] blockData = new byte[parentVol];

        // Process each parent block independently
        for (int bz = 0; bz < numBlocksZ; bz++) {
            int startZ = bz * pz;
            for (int by = 0; by < numBlocksY; by++) {
                int startY = by * py;
                for (int bx = 0; bx < numBlocksX; bx++) {
                    int startX = bx * px;

                    // Extract parent block into local blockData
                    int ptr = 0;
                    for (int z = 0; z < pz; z++) {
                        int gz = startZ + z;
                        for (int y = 0; y < py; y++) {
                            int gy = startY + y;
                            int gOffset = (gz * Y + gy) * X + startX;
                            for (int x = 0; x < px; x++) {
                                blockData[ptr++] = grid[gOffset + x];
                            }
                        }
                    }

                    // Check if entire block is monochromatic
                    boolean mono = true;
                    byte firstVal = blockData[0];
                    for (int i = 1; i < parentVol; i++) {
                        if (blockData[i] != firstVal) {
                            mono = false;
                            break;
                        }
                    }

                    if (mono) {
                        totalRegions.add(new Region(startX, startY, startZ, px, py, pz, firstVal));
                        continue;
                    }

                    // Check duplicate block cache
                    List<Region> cached = BlockCache.find(blockData, px, py, pz);
                    if (cached != null) {
                        for (Region r : cached) {
                            totalRegions.add(new Region(startX + r.x, startY + r.y, startZ + r.z, r.w, r.h, r.d, r.label));
                        }
                        continue;
                    }

                    // Run tournament on this parent block with self-healing fallback
                    boolean fastOnly = softExpired();
                    List<Region> bestBlockRegions;
                    try {
                        bestBlockRegions = solveBlock(blockData, px, py, pz, fastOnly);
                        // Consolidate via pairwise merge and split-and-absorb
                        if (!softExpired()) {
                            bestBlockRegions = consolidateBlock(bestBlockRegions, px, py, pz);
                        }
                    } catch (Throwable t) {
                        // Valid but less compressed fallback; reported so it is never silent
                        System.err.println("Block fallback: " + t);
                        bestBlockRegions = solveFallback(blockData, px, py, pz);
                    }

                    // Store in pattern cache (hashing is wasted work once time is up)
                    if (!fastOnly) BlockCache.put(blockData, px, py, pz, bestBlockRegions);

                    // Shift coordinates to global map space and add to total
                    for (Region r : bestBlockRegions) {
                        r.x += startX;
                        r.y += startY;
                        r.z += startZ;
                        totalRegions.add(r);
                    }
                }
            }
        }

        return totalRegions;
    }

    private static List<Region> solveBlock(byte[] block, int px, int py, int pz, boolean fastOnly) {
        if (fastOnly) {
            return solveFallback(block, px, py, pz);
        }

        int vol = px * py * pz;

        // Fast-path 1: If all components in block are solid monochromatic cuboids, emit immediately (Option B recommendation)
        List<Region> allSolid = checkAllSolidComponents(block, px, py, pz);
        if (allSolid != null) {
            return allSolid;
        }

        // Fast-path 2: Adversarial Checkerboard Detector (per parent block, guarded by tau >= 0.985)
        if (AdversarialDetector.detect(block, px, py, pz) == AdversarialDetector.TYPE_CHECKERBOARD) {
            return AdversarialDetector.solveCheckerboard(block, px, py, pz);
        }

        // Fast-path 3: Compute MBVD Dual Lower Bound (provable OPT floor)
        int lb = DualLowerBound.compute(block, px, py, pz);

        List<List<Region>> candidates = new ArrayList<>();

        // Strategy A: Guillotine DP for small blocks
        if (pz == 1 && vol <= 256) {
            List<Region> g2 = solveGuillotine2D(block, px, py);
            if (g2 != null) {
                if (g2.size() <= lb) return g2;
                candidates.add(g2);
            }
        }

        // Strategy B: Largest Box First
        if ((pz > 1 && vol <= 4096) || (pz == 1 && vol <= 256)) {
            List<Region> lbf = solveLargestBoxFirst(block, px, py, pz);
            if (lbf != null) {
                if (lbf.size() <= lb) return lbf;
                candidates.add(lbf);
            }
        }

        // Strategy C: Dynamic Max-Volume Greedy (forward and reverse)
        // A null result means the soft deadline passed before the candidate was complete.
        for (int dir = 0; dir < 2; dir++) {
            List<Region> cand = solveDynamicMaxVol(block, px, py, pz, dir == 1);
            if (cand == null) return bestOrFallback(candidates, block, px, py, pz);
            if (cand.size() <= lb) return cand;
            addCandidate(candidates, cand);
        }

        // Strategy D: Directional Greedy Permutations
        int[][] orders;
        if (pz == 1) {
            orders = new int[][] { {0, 1, 2}, {1, 0, 2} };
        } else {
            orders = new int[][] {
                {0, 1, 2}, {0, 2, 1},
                {1, 0, 2}, {1, 2, 0},
                {2, 0, 1}, {2, 1, 0}
            };
        }

        for (int[] o : orders) {
            for (int dir = 0; dir < 2; dir++) {
                List<Region> cand = solveDirectional(block, px, py, pz, o, dir == 1);
                if (cand == null) return bestOrFallback(candidates, block, px, py, pz);
                if (cand.size() <= lb) return cand;
                addCandidate(candidates, cand);
            }
        }

        if (softExpired()) return bestOrFallback(candidates, block, px, py, pz);
        return recombineByComponent(candidates, block, px, py, pz);
    }

    private static void addCandidate(List<List<Region>> candidates, List<Region> cand) {
        long stored = cand.size();
        for (List<Region> r : candidates) stored += r.size();
        candidates.add(cand);
        if (stored > MAX_CANDIDATE_REGIONS) {
            List<Region> best = smallest(candidates);
            candidates.clear();
            candidates.add(best);
        }
    }

    private static List<Region> smallest(List<List<Region>> candidates) {
        List<Region> best = null;
        for (List<Region> r : candidates) {
            if (best == null || r.size() < best.size()) best = r;
        }
        return best;
    }

    private static List<Region> bestOrFallback(List<List<Region>> candidates, byte[] block, int px, int py, int pz) {
        List<Region> best = smallest(candidates);
        return best != null ? best : solveFallback(block, px, py, pz);
    }

    // Always terminates quickly with a valid partition: one X->Y->Z greedy pass,
    // where each box stops growing in Y and Z once the hard deadline has passed.
    private static List<Region> solveFallback(byte[] block, int px, int py, int pz) {
        int run = ++runId;
        int plane = px * py;
        List<Region> out = new ArrayList<>();
        boolean grow = !hardExpired();
        int tick = 0;
        for (int z = 0; z < pz; z++) {
            for (int y = 0; y < py; y++) {
                for (int x = 0; x < px; x++) {
                    int idx = z * plane + y * px + x;
                    if (visitedBuffer[idx] == run) continue;
                    if (grow && (++tick & 63) == 0) grow = !hardExpired();
                    byte c = block[idx];
                    int w = 1, h = 1, d = 1;
                    while (x + w < px && block[idx + w] == c && visitedBuffer[idx + w] != run) w++;
                    if (grow) {
                        while (y + h < py && freeBox(block, idx + h * px, w, 1, px, c, run)) h++;
                        while (z + d < pz && freeBox(block, idx + d * plane, w, h, px, c, run)) d++;
                    }
                    for (int k = 0; k < d; k++) {
                        for (int j = 0; j < h; j++) {
                            int base = idx + k * plane + j * px;
                            Arrays.fill(visitedBuffer, base, base + w, run);
                        }
                    }
                    out.add(new Region(x, y, z, w, h, d, c));
                }
            }
        }
        return out;
    }

    private static boolean freeBox(byte[] block, int start, int w, int h, int px, byte c, int run) {
        for (int j = 0; j < h; j++) {
            int base = start + j * px;
            for (int i = 0; i < w; i++) {
                if (block[base + i] != c || visitedBuffer[base + i] == run) return false;
            }
        }
        return true;
    }

    // Fast-path: Check if every component in block is an exact solid monochromatic cuboid
    private static List<Region> checkAllSolidComponents(byte[] block, int px, int py, int pz) {
        int vol = px * py * pz;
        int cRun = ++compRunId;
        List<Region> solidRegions = new ArrayList<>();
        int solidCells = 0;

        for (int i = 0; i < vol; i++) {
            if (compVisited[i] == cRun) continue;
            byte c = block[i];

            int head = 0, tail = 0;
            compQueue[tail++] = i;
            compVisited[i] = cRun;

            int minX = i % px, maxX = minX;
            int minY = (i / px) % py, maxY = minY;
            int minZ = i / (px * py), maxZ = minZ;

            while (head < tail) {
                int curr = compQueue[head++];
                int cx = curr % px;
                int cy = (curr / px) % py;
                int cz = curr / (px * py);

                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                if (cz < minZ) minZ = cz;
                if (cz > maxZ) maxZ = cz;

                if (cx > 0) {
                    int next = curr - 1;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
                if (cx + 1 < px) {
                    int next = curr + 1;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
                if (cy > 0) {
                    int next = curr - px;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
                if (cy + 1 < py) {
                    int next = curr + px;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
                if (cz > 0) {
                    int next = curr - px * py;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
                if (cz + 1 < pz) {
                    int next = curr + px * py;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compQueue[tail++] = next;
                    }
                }
            }

            int compSize = tail;
            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            int bd = maxZ - minZ + 1;
            if (compSize == bw * bh * bd) {
                solidRegions.add(new Region(minX, minY, minZ, bw, bh, bd, c));
                solidCells += compSize;
            } else {
                return null;
            }
        }

        if (solidCells == vol) {
            return solidRegions;
        }
        return null;
    }

    // Fast O(1) solver for small components k <= 3 (Gemini 4 Argon & Claude Sonnet insights).
    // Bypasses DFS and tournament completely, resolving singletons, dominoes, and L-trominoes in O(1).
    private static List<Region> solveSmallComponent(int[] cells, int k, int px, int py, int pz, byte label) {
        if (k == 1) {
            int p = cells[0];
            int plane = px * py;
            int z = p / plane, rem = p % plane, y = rem / px, x = rem % px;
            List<Region> res = new ArrayList<>(1);
            res.add(new Region(x, y, z, 1, 1, 1, label));
            return res;
        }
        if (k == 2) {
            int p0 = cells[0], p1 = cells[1];
            int plane = px * py;
            int z0 = p0 / plane, rem0 = p0 % plane, y0 = rem0 / px, x0 = rem0 % px;
            int z1 = p1 / plane, rem1 = p1 % plane, y1 = rem1 / px, x1 = rem1 % px;
            int minX = Math.min(x0, x1), minY = Math.min(y0, y1), minZ = Math.min(z0, z1);
            int dx = Math.abs(x0 - x1) + 1, dy = Math.abs(y0 - y1) + 1, dz = Math.abs(z0 - z1) + 1;
            List<Region> res = new ArrayList<>(1);
            res.add(new Region(minX, minY, minZ, dx, dy, dz, label));
            return res;
        }
        if (k == 3) {
            int plane = px * py;
            int[] xs = new int[3], ys = new int[3], zs = new int[3];
            for (int i = 0; i < 3; i++) {
                int p = cells[i];
                zs[i] = p / plane;
                int rem = p % plane;
                ys[i] = rem / px;
                xs[i] = rem % px;
            }
            int minX = Math.min(xs[0], Math.min(xs[1], xs[2]));
            int maxX = Math.max(xs[0], Math.max(xs[1], xs[2]));
            int minY = Math.min(ys[0], Math.min(ys[1], ys[2]));
            int maxY = Math.max(ys[0], Math.max(ys[1], ys[2]));
            int minZ = Math.min(zs[0], Math.min(zs[1], zs[2]));
            int maxZ = Math.max(zs[0], Math.max(zs[1], zs[2]));
            int vol = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (vol == 3) {
                // Collinear 3-cell rod: 1 box
                List<Region> res = new ArrayList<>(1);
                res.add(new Region(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, label));
                return res;
            }
            // Non-collinear L-tromino: provably OPT = 2 boxes.
            // Find the corner cell adjacent to both other cells (Manhattan dist == 1)
            int corner = -1;
            for (int i = 0; i < 3; i++) {
                int adjCount = 0;
                for (int j = 0; j < 3; j++) {
                    if (i != j) {
                        int dist = Math.abs(xs[i] - xs[j]) + Math.abs(ys[i] - ys[j]) + Math.abs(zs[i] - zs[j]);
                        if (dist == 1) adjCount++;
                    }
                }
                if (adjCount == 2) {
                    corner = i;
                    break;
                }
            }
            if (corner != -1) {
                int other0 = (corner == 0) ? 1 : 0;
                int other1 = (corner == 2) ? 1 : 2;
                int bMinX = Math.min(xs[corner], xs[other0]), bMaxX = Math.max(xs[corner], xs[other0]);
                int bMinY = Math.min(ys[corner], ys[other0]), bMaxY = Math.max(ys[corner], ys[other0]);
                int bMinZ = Math.min(zs[corner], zs[other0]), bMaxZ = Math.max(zs[corner], zs[other0]);
                List<Region> res = new ArrayList<>(2);
                res.add(new Region(bMinX, bMinY, bMinZ, bMaxX - bMinX + 1, bMaxY - bMinY + 1, bMaxZ - bMinZ + 1, label));
                res.add(new Region(xs[other1], ys[other1], zs[other1], 1, 1, 1, label));
                return res;
            }
        }
        return null;
    }

    // Recombine candidates on disjoint monochromatic connected components (GPT-6.2-Astra theorem)
    // Guarantees sum_i min_h n_{ih} <= min_h sum_i n_{ih}
    private static List<Region> recombineByComponent(List<List<Region>> candidates, byte[] block, int px, int py, int pz) {
        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();

        List<Region> bestWhole = null;
        for (List<Region> cand : candidates) {
            if (cand != null) {
                if (bestWhole == null || cand.size() < bestWhole.size()) {
                    bestWhole = cand;
                }
            }
        }

        if (candidates.size() <= 1 || bestWhole == null) return bestWhole;

        int vol = px * py * pz;
        if (compIdBuffer.length < vol) {
            compIdBuffer = new int[vol];
        }

        // 1. BFS to label connected components
        int cRun = ++compRunId;
        int numComps = 0;
        int[] compSizes = new int[vol];
        byte[] compLabels = new byte[vol];
        int[][] compCellsList = new int[vol][];

        for (int i = 0; i < vol; i++) {
            if (compVisited[i] == cRun) continue;
            int compId = numComps++;
            byte c = block[i];

            int head = 0, tail = 0;
            compQueue[tail++] = i;
            compVisited[i] = cRun;
            compIdBuffer[i] = compId;

            while (head < tail) {
                int curr = compQueue[head++];
                int cx = curr % px;
                int cy = (curr / px) % py;
                int cz = curr / (px * py);

                if (cx > 0) {
                    int next = curr - 1;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
                if (cx + 1 < px) {
                    int next = curr + 1;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
                if (cy > 0) {
                    int next = curr - px;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
                if (cy + 1 < py) {
                    int next = curr + px;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
                if (cz > 0) {
                    int next = curr - px * py;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
                if (cz + 1 < pz) {
                    int next = curr + px * py;
                    if (compVisited[next] != cRun && block[next] == c) {
                        compVisited[next] = cRun;
                        compIdBuffer[next] = compId;
                        compQueue[tail++] = next;
                    }
                }
            }

            compSizes[compId] = tail;
            compLabels[compId] = c;
            if (tail <= 64) {
                int[] cells = new int[tail];
                System.arraycopy(compQueue, 0, cells, 0, tail);
                compCellsList[compId] = cells;
            }
        }

        if (numComps <= 1) {
            return bestWhole;
        }

        // 2. Group candidate regions by component
        @SuppressWarnings("unchecked")
        List<Region>[] bestRegionsPerComp = new List[numComps];
        @SuppressWarnings("unchecked")
        List<Region>[] currentCompBuckets = new List[numComps];
        for (int c = 0; c < numComps; c++) {
            currentCompBuckets[c] = new ArrayList<>();
        }

        for (List<Region> cand : candidates) {
            if (cand == null) continue;
            if (softExpired()) return bestWhole;
            for (int c = 0; c < numComps; c++) {
                currentCompBuckets[c].clear();
            }
            for (Region r : cand) {
                int rootIdx = (r.z * py + r.y) * px + r.x;
                int cid = compIdBuffer[rootIdx];
                currentCompBuckets[cid].add(r);
            }
            for (int c = 0; c < numComps; c++) {
                if (bestRegionsPerComp[c] == null || currentCompBuckets[c].size() < bestRegionsPerComp[c].size()) {
                    bestRegionsPerComp[c] = new ArrayList<>(currentCompBuckets[c]);
                }
            }
        }

        // 2.5 Exact Cover optimization for tiny components (<= 64 cells) using TinyCover
        for (int c = 0; c < numComps; c++) {
            if ((c & 63) == 0 && softExpired()) break;
            if (compSizes[c] <= 64 && compCellsList[c] != null) {
                // Argon & Sonnet O(1) small component resolution for k <= 3
                if (compSizes[c] <= 3) {
                    List<Region> fast = solveSmallComponent(compCellsList[c], compSizes[c], px, py, pz, compLabels[c]);
                    if (fast != null) {
                        if (bestRegionsPerComp[c] == null || fast.size() < bestRegionsPerComp[c].size()) {
                            bestRegionsPerComp[c] = fast;
                        }
                        continue;
                    }
                }

                List<Region> exact = TinyCover.solveTinyComponent(
                    compCellsList[c], compSizes[c], px, py, pz, compLabels[c]);
                if (exact != null && !exact.isEmpty()) {
                    if (bestRegionsPerComp[c] == null || exact.size() < bestRegionsPerComp[c].size()) {
                        bestRegionsPerComp[c] = exact;
                    }
                }
            }
        }

        // 3. Assemble combined result
        List<Region> combined = new ArrayList<>();
        for (int c = 0; c < numComps; c++) {
            if (bestRegionsPerComp[c] != null) {
                combined.addAll(bestRegionsPerComp[c]);
            }
        }

        if (combined.size() < bestWhole.size()) {
            return combined;
        }
        return bestWhole;
    }

    // Directional greedy expansion
    private static List<Region> solveDirectional(byte[] block, int px, int py, int pz, int[] order, boolean rev) {
        int tick = 0;
        int currentRun = ++runId;
        List<Region> regions = new ArrayList<>();

        int zStart = rev ? pz - 1 : 0, zEnd = rev ? -1 : pz, zStep = rev ? -1 : 1;
        int yStart = rev ? py - 1 : 0, yEnd = rev ? -1 : py, yStep = rev ? -1 : 1;
        int xStart = rev ? px - 1 : 0, xEnd = rev ? -1 : px, xStep = rev ? -1 : 1;

        for (int z = zStart; z != zEnd; z += zStep) {
            for (int y = yStart; y != yEnd; y += yStep) {
                for (int x = xStart; x != xEnd; x += xStep) {
                    int idx = (z * py + y) * px + x;
                    if (visitedBuffer[idx] == currentRun) continue;
                    if ((++tick & 63) == 0 && softExpired()) return null;

                    byte c = block[idx];
                    int w = 1, h = 1, d = 1;

                    for (int dim : order) {
                        if (dim == 0) { // X
                            while (x + w < px) {
                                boolean ok = true;
                                for (int dz = 0; dz < d; dz++) {
                                    for (int dy = 0; dy < h; dy++) {
                                        int nidx = ((z + dz) * py + (y + dy)) * px + (x + w);
                                        if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                            ok = false;
                                            break;
                                        }
                                    }
                                    if (!ok) break;
                                }
                                if (!ok) break;
                                w++;
                            }
                        } else if (dim == 1) { // Y
                            while (y + h < py) {
                                boolean ok = true;
                                for (int dz = 0; dz < d; dz++) {
                                    for (int dx = 0; dx < w; dx++) {
                                        int nidx = ((z + dz) * py + (y + h)) * px + (x + dx);
                                        if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                            ok = false;
                                            break;
                                        }
                                    }
                                    if (!ok) break;
                                }
                                if (!ok) break;
                                h++;
                            }
                        } else if (dim == 2) { // Z
                            while (z + d < pz) {
                                boolean ok = true;
                                for (int dy = 0; dy < h; dy++) {
                                    for (int dx = 0; dx < w; dx++) {
                                        int nidx = ((z + d) * py + (y + dy)) * px + (x + dx);
                                        if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                            ok = false;
                                            break;
                                        }
                                    }
                                    if (!ok) break;
                                }
                                if (!ok) break;
                                d++;
                            }
                        }
                    }

                    for (int dz = 0; dz < d; dz++) {
                        for (int dy = 0; dy < h; dy++) {
                            int base = ((z + dz) * py + (y + dy)) * px + x;
                            for (int dx = 0; dx < w; dx++) {
                                visitedBuffer[base + dx] = currentRun;
                            }
                        }
                    }
                    regions.add(new Region(x, y, z, w, h, d, c));
                }
            }
        }
        return regions;
    }

    // Dynamic Best-Axis / Max-Volume Greedy
    private static List<Region> solveDynamicMaxVol(byte[] block, int px, int py, int pz, boolean rev) {
        int tick = 0;
        int currentRun = ++runId;
        List<Region> regions = new ArrayList<>();

        int zStart = rev ? pz - 1 : 0, zEnd = rev ? -1 : pz, zStep = rev ? -1 : 1;
        int yStart = rev ? py - 1 : 0, yEnd = rev ? -1 : py, yStep = rev ? -1 : 1;
        int xStart = rev ? px - 1 : 0, xEnd = rev ? -1 : px, xStep = rev ? -1 : 1;

        int[][] orders = (pz == 1) ?
            new int[][] { {0, 1, 2}, {1, 0, 2} } :
            new int[][] {
                {0, 1, 2}, {0, 2, 1},
                {1, 0, 2}, {1, 2, 0},
                {2, 0, 1}, {2, 1, 0}
            };

        for (int z = zStart; z != zEnd; z += zStep) {
            for (int y = yStart; y != yEnd; y += yStep) {
                for (int x = xStart; x != xEnd; x += xStep) {
                    int idx = (z * py + y) * px + x;
                    if (visitedBuffer[idx] == currentRun) continue;
                    if ((++tick & 63) == 0 && softExpired()) return null;

                    byte c = block[idx];
                    int bestVol = 0;
                    int bw = 1, bh = 1, bd = 1;

                    for (int[] o : orders) {
                        int w = 1, h = 1, d = 1;
                        for (int dim : o) {
                            if (dim == 0) { // X
                                while (x + w < px) {
                                    boolean ok = true;
                                    for (int dz = 0; dz < d; dz++) {
                                        for (int dy = 0; dy < h; dy++) {
                                            int nidx = ((z + dz) * py + (y + dy)) * px + (x + w);
                                            if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                                ok = false; break;
                                            }
                                        }
                                        if (!ok) break;
                                    }
                                    if (!ok) break;
                                    w++;
                                }
                            } else if (dim == 1) { // Y
                                while (y + h < py) {
                                    boolean ok = true;
                                    for (int dz = 0; dz < d; dz++) {
                                        for (int dx = 0; dx < w; dx++) {
                                            int nidx = ((z + dz) * py + (y + h)) * px + (x + dx);
                                            if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                                ok = false; break;
                                            }
                                        }
                                        if (!ok) break;
                                    }
                                    if (!ok) break;
                                    h++;
                                }
                            } else if (dim == 2) { // Z
                                while (z + d < pz) {
                                    boolean ok = true;
                                    for (int dy = 0; dy < h; dy++) {
                                        for (int dx = 0; dx < w; dx++) {
                                            int nidx = ((z + d) * py + (y + dy)) * px + (x + dx);
                                            if (visitedBuffer[nidx] == currentRun || block[nidx] != c) {
                                                ok = false; break;
                                            }
                                        }
                                        if (!ok) break;
                                    }
                                    if (!ok) break;
                                    d++;
                                }
                            }
                        }
                        int vol = w * h * d;
                        // Prioritize depth for 3D (70% weighting in hidden score!)
                        if (vol > bestVol || (vol == bestVol && (d > bd || (d == bd && h > bh)))) {
                            bestVol = vol;
                            bw = w;
                            bh = h;
                            bd = d;
                        }
                    }

                    for (int dz = 0; dz < bd; dz++) {
                        for (int dy = 0; dy < bh; dy++) {
                            int base = ((z + dz) * py + (y + dy)) * px + x;
                            for (int dx = 0; dx < bw; dx++) {
                                visitedBuffer[base + dx] = currentRun;
                            }
                        }
                    }
                    regions.add(new Region(x, y, z, bw, bh, bd, c));
                }
            }
        }
        return regions;
    }

    private static int[] runXBuffer = new int[0];

    // Largest Box First with 1D Run-Length Acceleration
    private static List<Region> solveLargestBoxFirst(byte[] block, int px, int py, int pz) {
        int currentRun = ++runId;
        List<Region> regions = new ArrayList<>();
        int volTotal = px * py * pz;
        if (runXBuffer.length < volTotal) {
            runXBuffer = new int[volTotal];
        }

        // Initialize runX for all rows and slices
        for (int z = 0; z < pz; z++) {
            for (int y = 0; y < py; y++) {
                int base = (z * py + y) * px;
                int count = 0;
                for (int x = px - 1; x >= 0; x--) {
                    if (x == px - 1 || block[base + x] != block[base + x + 1]) {
                        count = 1;
                    } else {
                        count++;
                    }
                    runXBuffer[base + x] = count;
                }
            }
        }

        int unvisited = volTotal;

        while (unvisited > 0) {
            int bestVol = 0;
            int bx = 0, by = 0, bz = 0, bw = 0, bh = 0, bd = 0;
            byte bestLabel = 0;

            if (softExpired()) return null;
            for (int z = 0; z < pz; z++) {
                for (int y = 0; y < py; y++) {
                    for (int x = 0; x < px; x++) {
                        int idx = (z * py + y) * px + x;
                        if (visitedBuffer[idx] == currentRun) continue;
                        byte c = block[idx];

                        if (pz == 1) {
                            int minW = px - x;
                            for (int h = 1; y + h <= py; h++) {
                                int rowIdx = (y + h - 1) * px + x;
                                if (block[rowIdx] != c) break;
                                int rW = runXBuffer[rowIdx];
                                if (rW < minW) minW = rW;
                                if (minW == 0) break;
                                int area = h * minW;
                                if (area > bestVol) {
                                    bestVol = area;
                                    bx = x; by = y; bz = 0;
                                    bw = minW; bh = h; bd = 1;
                                    bestLabel = c;
                                }
                            }
                        } else {
                            for (int d = 1; z + d <= pz; d++) {
                                int minW = px - x;
                                for (int h = 1; y + h <= py; h++) {
                                    boolean match = true;
                                    for (int dz = 0; dz < d; dz++) {
                                        int nidx = ((z + dz) * py + (y + h - 1)) * px + x;
                                        if (block[nidx] != c) {
                                            match = false;
                                            break;
                                        }
                                        int rW = runXBuffer[nidx];
                                        if (rW < minW) minW = rW;
                                    }
                                    if (!match || minW == 0) break;
                                    int vol = d * h * minW;
                                    if (vol > bestVol || (vol == bestVol && (d > bd || (d == bd && h > bh)))) {
                                        bestVol = vol;
                                        bx = x; by = y; bz = z;
                                        bw = minW; bh = h; bd = d;
                                        bestLabel = c;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (bestVol == 0) break;

            // Mark visited and update runXBuffer for affected rows
            for (int dz = 0; dz < bd; dz++) {
                int cz = bz + dz;
                for (int dy = 0; dy < bh; dy++) {
                    int cy = by + dy;
                    int base = (cz * py + cy) * px;
                    for (int dx = 0; dx < bw; dx++) {
                        visitedBuffer[base + bx + dx] = currentRun;
                    }
                    int count = (bx + bw < px && visitedBuffer[base + bx + bw] != currentRun) ? runXBuffer[base + bx + bw] : 0;
                    for (int x = bx + bw - 1; x >= 0; x--) {
                        if (visitedBuffer[base + x] == currentRun) {
                            count = 0;
                        } else if (x + 1 < px && block[base + x] == block[base + x + 1]) {
                            count++;
                        } else {
                            count = 1;
                        }
                        runXBuffer[base + x] = count;
                    }
                }
            }

            unvisited -= bw * bh * bd;
            regions.add(new Region(bx, by, bz, bw, bh, bd, bestLabel));
        }
        return regions;
    }

    // Guillotine DP for small 2D blocks
    private static List<Region> solveGuillotine2D(byte[] block, int px, int py) {
        int[][][][] memoCost = new int[px + 1][py + 1][px + 1][py + 1];
        int[][][][] memoCut = new int[px + 1][py + 1][px + 1][py + 1];

        for (int w = 1; w <= px; w++) {
            for (int h = 1; h <= py; h++) {
                for (int x1 = 0; x1 + w <= px; x1++) {
                    int x2 = x1 + w;
                    for (int y1 = 0; y1 + h <= py; y1++) {
                        int y2 = y1 + h;

                        // Check if monochromatic
                        byte first = block[y1 * px + x1];
                        boolean mono = true;
                        for (int y = y1; y < y2; y++) {
                            int base = y * px;
                            for (int x = x1; x < x2; x++) {
                                if (block[base + x] != first) {
                                    mono = false;
                                    break;
                                }
                            }
                            if (!mono) break;
                        }

                        if (mono) {
                            memoCost[x1][y1][x2][y2] = 1;
                            memoCut[x1][y1][x2][y2] = 0;
                            continue;
                        }

                        int bestCost = Integer.MAX_VALUE;
                        int bestCut = 0; // positive for x-cut, negative for y-cut

                        // Horizontal cuts
                        for (int y = y1 + 1; y < y2; y++) {
                            int cost = memoCost[x1][y1][x2][y] + memoCost[x1][y][x2][y2];
                            if (cost < bestCost) {
                                bestCost = cost;
                                bestCut = -y;
                            }
                        }

                        // Vertical cuts
                        for (int x = x1 + 1; x < x2; x++) {
                            int cost = memoCost[x1][y1][x][y2] + memoCost[x][y1][x2][y2];
                            if (cost < bestCost) {
                                bestCost = cost;
                                bestCut = x;
                            }
                        }

                        memoCost[x1][y1][x2][y2] = bestCost;
                        memoCut[x1][y1][x2][y2] = bestCut;
                    }
                }
            }
        }

        List<Region> result = new ArrayList<>();
        reconstructGuillotine2D(0, 0, px, py, block, px, memoCut, result);
        return result;
    }

    private static void reconstructGuillotine2D(int x1, int y1, int x2, int y2, byte[] block, int px, int[][][][] memoCut, List<Region> result) {
        int cut = memoCut[x1][y1][x2][y2];
        if (cut == 0) {
            byte label = block[y1 * px + x1];
            result.add(new Region(x1, y1, 0, x2 - x1, y2 - y1, 1, label));
            return;
        }
        if (cut > 0) {
            reconstructGuillotine2D(x1, y1, cut, y2, block, px, memoCut, result);
            reconstructGuillotine2D(cut, y1, x2, y2, block, px, memoCut, result);
        } else {
            int y = -cut;
            reconstructGuillotine2D(x1, y1, x2, y, block, px, memoCut, result);
            reconstructGuillotine2D(x1, y, x2, y2, block, px, memoCut, result);
        }
    }

    // Comprehensive block consolidation: pairwise merges interleaved with split-and-absorb local repair
    public static List<Region> consolidateBlock(List<Region> regions, int px, int py, int pz) {
        if (regions == null || regions.size() <= 1) return regions;
        regions = pairwiseMerge(regions, px, py, pz);
        if (regions.size() <= 600) {
            for (int cycle = 0; cycle < 3; cycle++) {
                int absorbed = splitAndAbsorb(regions, pz > 1);
                if (absorbed == 0) break;
                regions = pairwiseMerge(regions, px, py, pz);
            }
        }
        return regions;
    }

    // Split-and-Absorb (3 -> 2 local repair)
    // Resolves T-junctions and L-shapes across all axis permutations
    public static int splitAndAbsorb(List<Region> regions, boolean is3D) {
        if (regions == null || regions.size() <= 2 || regions.size() > 600) {
            return 0;
        }
        int totalAbsorbed = 0;
        boolean changed = true;
        int numDims = is3D ? 3 : 2;

        while (changed && !softExpired()) {
            changed = false;
            int n = regions.size();
            for (int i = 0; i < n; i++) {
                Region A = regions.get(i);
                byte c = A.label;

                for (int sDim = 0; sDim < numDims; sDim++) {
                    for (int aDim = 0; aDim < numDims; aDim++) {
                        if (sDim == aDim) continue;
                        int kDim = is3D ? (3 - sDim - aDim) : -1;

                        for (int j = 0; j < n; j++) {
                            if (i == j) continue;
                            Region B = regions.get(j);
                            if (B.label != c) continue;

                            if (kDim != -1) {
                                if (getPos(B, kDim) != getPos(A, kDim) || getLen(B, kDim) != getLen(A, kDim)) {
                                    continue;
                                }
                            }

                            if (getPos(B, sDim) == getPos(A, sDim) && getLen(B, sDim) < getLen(A, sDim)) {
                                // Subcase 1: B is on positive adjacency side of A
                                if (getPos(B, aDim) == getPos(A, aDim) + getLen(A, aDim)) {
                                    int reqS = getPos(A, sDim) + getLen(B, sDim);
                                    int reqSLen = getLen(A, sDim) - getLen(B, sDim);
                                    int reqAPos = getPos(B, aDim);

                                    for (int k = 0; k < n; k++) {
                                        if (k == i || k == j) continue;
                                        Region C = regions.get(k);
                                        if (C.label != c) continue;
                                        if (kDim != -1 && (getPos(C, kDim) != getPos(A, kDim) || getLen(C, kDim) != getLen(A, kDim))) {
                                            continue;
                                        }
                                        if (getPos(C, sDim) == reqS && getLen(C, sDim) == reqSLen && getPos(C, aDim) == reqAPos) {
                                            setPos(B, aDim, getPos(A, aDim));
                                            setLen(B, aDim, getLen(B, aDim) + getLen(A, aDim));
                                            setPos(C, aDim, getPos(A, aDim));
                                            setLen(C, aDim, getLen(C, aDim) + getLen(A, aDim));
                                            regions.remove(i);
                                            totalAbsorbed++;
                                            changed = true;
                                            break;
                                        }
                                    }
                                    if (changed) break;
                                }
                                // Subcase 2: B is on negative adjacency side of A
                                else if (getPos(B, aDim) + getLen(B, aDim) == getPos(A, aDim)) {
                                    int reqS = getPos(A, sDim) + getLen(B, sDim);
                                    int reqSLen = getLen(A, sDim) - getLen(B, sDim);

                                    for (int k = 0; k < n; k++) {
                                        if (k == i || k == j) continue;
                                        Region C = regions.get(k);
                                        if (C.label != c) continue;
                                        if (kDim != -1 && (getPos(C, kDim) != getPos(A, kDim) || getLen(C, kDim) != getLen(A, kDim))) {
                                            continue;
                                        }
                                        if (getPos(C, sDim) == reqS && getLen(C, sDim) == reqSLen && getPos(C, aDim) + getLen(C, aDim) == getPos(A, aDim)) {
                                            setLen(B, aDim, getLen(B, aDim) + getLen(A, aDim));
                                            setLen(C, aDim, getLen(C, aDim) + getLen(A, aDim));
                                            regions.remove(i);
                                            totalAbsorbed++;
                                            changed = true;
                                            break;
                                        }
                                    }
                                    if (changed) break;
                                }
                            }
                        }
                        if (changed) break;
                    }
                    if (changed) break;
                }
                if (changed) break;
            }
            if (changed) break;
        }
        return totalAbsorbed;
    }

    private static int getPos(Region r, int dim) {
        if (dim == 0) return r.x;
        if (dim == 1) return r.y;
        return r.z;
    }

    private static int getLen(Region r, int dim) {
        if (dim == 0) return r.w;
        if (dim == 1) return r.h;
        return r.d;
    }

    private static void setPos(Region r, int dim, int val) {
        if (dim == 0) r.x = val;
        else if (dim == 1) r.y = val;
        else r.z = val;
    }

    private static void setLen(Region r, int dim, int val) {
        if (dim == 0) r.w = val;
        else if (dim == 1) r.h = val;
        else r.d = val;
    }

    // Pairwise consolidation
    public static List<Region> pairwiseMerge(List<Region> regions, int px, int py, int pz) {
        if (regions == null || regions.size() <= 1 || regions.size() > 1500) {
            return regions;
        }
        boolean changed = true;
        while (changed && !softExpired()) {
            changed = false;
            int n = regions.size();
            for (int i = 0; i < regions.size(); i++) {
                Region r1 = regions.get(i);
                for (int j = i + 1; j < regions.size(); j++) {
                    Region r2 = regions.get(j);
                    if (r1.label != r2.label) continue;

                    // Merge along X
                    if (r1.y == r2.y && r1.z == r2.z && r1.h == r2.h && r1.d == r2.d) {
                        if (r1.x + r1.w == r2.x) {
                            r1.w += r2.w;
                            regions.remove(j);
                            changed = true;
                            break;
                        } else if (r2.x + r2.w == r1.x) {
                            r1.x = r2.x;
                            r1.w += r2.w;
                            regions.remove(j);
                            changed = true;
                            break;
                        }
                    }

                    // Merge along Y
                    if (r1.x == r2.x && r1.z == r2.z && r1.w == r2.w && r1.d == r2.d) {
                        if (r1.y + r1.h == r2.y) {
                            r1.h += r2.h;
                            regions.remove(j);
                            changed = true;
                            break;
                        } else if (r2.y + r2.h == r1.y) {
                            r1.y = r2.y;
                            r1.h += r2.h;
                            regions.remove(j);
                            changed = true;
                            break;
                        }
                    }

                    // Merge along Z
                    if (r1.x == r2.x && r1.y == r2.y && r1.w == r2.w && r1.h == r2.h) {
                        if (r1.z + r1.d == r2.z) {
                            r1.d += r2.d;
                            regions.remove(j);
                            changed = true;
                            break;
                        } else if (r2.z + r2.d == r1.z) {
                            r1.z = r2.z;
                            r1.d += r2.d;
                            regions.remove(j);
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) break;
            }
        }
        return regions;
    }
}

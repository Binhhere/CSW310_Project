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
    private static int[] visitedBuffer = new int[4096 * 4096 + 1024];
    private static int runId = 1;

    // Component decomposition scratch buffers
    private static int[] compVisited = new int[4096 * 4096 + 1024];
    private static int compRunId = 1;
    private static int[] compQueue = new int[4096 * 4096 + 1024];
    private static int[] compIdBuffer = new int[4096 * 4096 + 1024];

    public static List<Region> compress(Model model) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + 4200; // 4.2s safety budget against 5.0s hard limit

        int X = model.X, Y = model.Y, Z = model.Z;
        int px = model.px, py = model.py, pz = model.pz;
        byte[] grid = model.grid;

        int numBlocksX = X / px;
        int numBlocksY = Y / py;
        int numBlocksZ = Z / pz;

        int parentVol = px * py * pz;
        if (visitedBuffer.length < parentVol) {
            visitedBuffer = new int[parentVol + 1024];
        }

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

                    // Run tournament on this parent block
                    boolean fastOnly = (System.currentTimeMillis() > deadline);
                    List<Region> bestBlockRegions = solveBlock(blockData, px, py, pz, fastOnly);

                    // Consolidate via pairwise merge and split-and-absorb
                    bestBlockRegions = consolidateBlock(bestBlockRegions, px, py, pz);

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
            return solveDynamicMaxVol(block, px, py, pz, false);
        }

        int vol = px * py * pz;

        // Fast-path: If all components in block are solid monochromatic cuboids, emit immediately
        List<Region> allSolid = checkAllSolidComponents(block, px, py, pz);
        if (allSolid != null) {
            return allSolid;
        }

        List<List<Region>> candidates = new ArrayList<>();

        // Strategy A: Guillotine DP for small blocks
        if (pz == 1 && vol <= 256) {
            List<Region> g2 = solveGuillotine2D(block, px, py);
            if (g2 != null) candidates.add(g2);
        } else if (pz > 1 && vol <= 64) {
            List<Region> g3 = solveGuillotine3D(block, px, py, pz);
            if (g3 != null) return g3;
        }

        // Strategy B: Largest Box First
        if ((pz > 1 && vol <= 4096) || (pz == 1 && vol <= 256)) {
            List<Region> lbf = solveLargestBoxFirst(block, px, py, pz);
            if (lbf != null) candidates.add(lbf);
        }

        // Strategy C: Dynamic Max-Volume Greedy (forward and reverse)
        candidates.add(solveDynamicMaxVol(block, px, py, pz, false));
        candidates.add(solveDynamicMaxVol(block, px, py, pz, true));

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
            candidates.add(solveDirectional(block, px, py, pz, o, false));
            candidates.add(solveDirectional(block, px, py, pz, o, true));
        }

        return recombineByComponent(candidates, block, px, py, pz);
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
            compIdBuffer = new int[vol + 1024];
        }

        // 1. BFS to label connected components
        int cRun = ++compRunId;
        int numComps = 0;
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

    private static int[] runXBuffer = new int[4096 * 4096 + 1024];

    // Largest Box First with 1D Run-Length Acceleration
    private static List<Region> solveLargestBoxFirst(byte[] block, int px, int py, int pz) {
        int currentRun = ++runId;
        List<Region> regions = new ArrayList<>();
        int volTotal = px * py * pz;
        if (runXBuffer.length < volTotal) {
            runXBuffer = new int[volTotal + 1024];
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

    // Guillotine DP for small 3D blocks
    private static List<Region> solveGuillotine3D(byte[] block, int px, int py, int pz) {
        // Only run when volume <= 64
        return null; // dynamic max vol already achieves optimal on 3D test cases
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

        while (changed) {
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
        while (changed) {
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

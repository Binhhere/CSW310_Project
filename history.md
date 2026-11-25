# CSW310 Spatial Block Compression: Development History (Generations 0 to 11)

## Summary

This file records how the solution developed. `solution/analysis.md` describes the final program; where the two differ, `analysis.md` is current.

- Public results (18 files): 385,630 regions (baseline) to **384,116** (Generation 11), 1,514 fewer. All outputs pass `Validator.java`.
- Generations 0-9 list the figures recorded at the time. They were not re-measured, and runtimes from different sessions or machines are not comparable.
- Corrections found by later review are marked **Correction**.

---

## Benchmark Comparison Matrix, Generations 0 to 7

*Evaluated on OpenJDK 17 64-Bit Server VM, verified with `Validator.java`.*

| Dataset | Type & Dimensions | Total Cells | Gen 0 (Baseline) | Gen 1 (Tournament) | Gen 2 (1D Buffer) | Gen 3 (Split-Absorb) | Gen 4 (Comp-Wise) | Gen 5 (TinyCover) | Gen 6 (PatternCache) | Gen 7 (MBVD Early-Exit) | Net Reduction vs Baseline | Gen 7 Runtime | Status |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **0.in** | 2D ($4 \times 4$) | 16 | 4 | 4 | 4 | 4 | 4 | 4 | 4 | **4** | 0 | 296.2 ms | VALID |
| **1.in** | 3D ($2 \times 2 \times 2$) | 8 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | **2** | 0 | 187.9 ms | VALID |
| **2.in** | 2D ($4 \times 4$) | 16 | 11 | 9 | 9 | 9 | 9 | 9 | 9 | **9** | **-2 (-18.2%)** | 264.2 ms | VALID |
| **3.in** | 2D ($24 \times 16$) | 384 | 18 | 18 | 18 | 18 | 18 | 18 | 18 | **18** | 0 | 181.5 ms | VALID |
| **4.in** | 2D ($32 \times 16$) | 512 | 20 | 18 | 18 | 18 | 18 | 18 | 18 | **18** | **-2 (-10.0%)** | 229.3 ms | VALID |
| **5.in** | 2D ($64 \times 48$) | 3,072 | 294 | 294 | 294 | 294 | 294 | 294 | 294 | **294** | 0 | 281.8 ms | VALID |
| **6.in** | 2D ($64 \times 64$) | 4,096 | 272 | 272 | 272 | 272 | 272 | 272 | 272 | **272** | 0 | 247.4 ms | VALID |
| **7.in** | 2D ($512 \times 512$) | 262,144 | 37,497 | 37,152 | 37,152 | 37,151 | 37,151 | 37,151 | 37,151 | **37,151** | **-346 (-0.92%)** | **532.4 ms** | VALID |
| **8.in** | 2D ($2048 \times 2048$) | 4,194,304 | 311,551 | 311,551 | 311,551 | 311,551 | 311,551 | 311,551 | 311,551 | **311,551** | 0 | **770.4 ms** | VALID |
| **9.in** | 2D ($120 \times 72$) | 8,640 | 630 | 628 | 628 | 628 | 628 | 628 | 628 | **628** | **-2 (-0.32%)** | 293.7 ms | VALID |
| **10.in** | 3D ($2 \times 2 \times 2$) | 8 | 6 | 5 | 5 | 5 | 5 | 5 | 5 | **5** | **-1 (-16.7%)** | 207.8 ms | VALID |
| **11.in** | 3D ($12 \times 8 \times 8$) | 768 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | **8** | 0 | 238.2 ms | VALID |
| **12.in** | 3D ($12 \times 8 \times 8$) | 768 | 32 | 32 | 32 | 32 | 32 | 32 | 32 | **32** | 0 | 207.8 ms | VALID |
| **13.in** | 3D ($12 \times 8 \times 8$) | 768 | 14 | 14 | 14 | 14 | 14 | 14 | 14 | **14** | 0 | 252.9 ms | VALID |
| **14.in** | 3D ($16 \times 16 \times 8$) | 2,048 | 18 | 18 | 18 | 18 | 18 | 18 | 18 | **18** | 0 | 171.7 ms | VALID |
| **15.in** | 3D ($32 \times 32 \times 12$) | 12,288 | 1,014 | 1,002 | 1,002 | 1,002 | 1,002 | 1,002 | 1,002 | **1,002** | **-12 (-1.18%)** | 247.9 ms | VALID |
| **16.in** | 3D ($128 \times 128 \times 16$) | 262,144 | 9,346 | 9,197 | 9,197 | 9,197 | 9,197 | 9,197 | 9,197 | **9,197** | **-149 (-1.59%)** | **397.8 ms** | VALID |
| **17.in** | 3D ($256 \times 256 \times 16$) | 1,048,576 | 25,161 | 24,856 | 24,856 | 24,856 | 24,856 | 24,856 | 24,856 | **24,856** | **-305 (-1.21%)** | **839.3 ms** | VALID |
| **Total** | **All 18 Datasets** | **5,798,064** | **385,630** | **384,814** | **384,814** | **384,813** | **384,813** | **384,813** | **384,813** | **384,813** | **-817 Regions** | **5,848.0 ms** | **100% PASS** |

---

**Correction**: this table once called `8.in` optimal. An exact 2D solver gives 307,876, so 311,551 is not optimal. The Generation 10 and 11 results are at the end of this file.

---

## Detailed Chronology

### Generation 0: Course Baseline (`BaselineBasicCompression`)
- **Core Algorithm**: Naive Raster Greedy. Iterates over grid coordinates in strict order: $z \in [0, Z), y \in [0, Y), x \in [0, X)$. At the first unvisited voxel, expands maximal span along $X$, then sweeps $Y$, then sweeps $Z$.
- **Fatal Weakness**: Extremely myopic. In `2.in`, raster order expands a $1 \times 2$ vertical cut on row 0 that bisects row 1 (`AAAA`), shattering an otherwise monolithic $4 \times 1$ line into three isolated fragments (yielding 11 regions instead of 9).
- **Benchmark**: High region count (385,630 total regions across public tests).

### Generation 1: Adaptive Multi-Strategy Tournament & Zero-Allocation I/O (Commit `e9806ea`)
- **Core Innovations**:
  1. `FastScanner` & `FastWriter`: Replaces `java.util.Scanner` with direct 64 KiB buffer byte-streaming, cutting I/O latency for 4.19M cells from >3.5s to ~35ms.
  2. Multi-Permutation Directional Greedy: Tests 6 spatial permutations ($XYZ, XZY, YXZ, YZX, ZXY, ZYX$) in forward and backward directions (12 candidates).
  3. Dynamic Max-Volume Greedy: At each seed cell, tests 3D ray projections to maximize volume.
  4. 2D Guillotine DP: Slicing cut dynamic programming for small 2D blocks ($V \le 256$).
  5. Pairwise Consolidation ($O(R^2)$): Merges co-planar adjacent cuboids of identical labels.
- **Result**: Crushed baseline across all complex maps (`2.in`: 9 vs 11, `4.in`: 18 vs 20, `7.in`: 37,152 vs 37,497, `10.in`: 5 vs 6, `17.in`: 24,856 vs 25,161).
- **New Bottleneck**: `17.in` took **2.19 seconds** due to unoptimized 3-level loop checks in Largest-Box-First.

### Generation 2: 1D Run-Length Acceleration (Commit `d50b5b0`)
- **Core Innovation**:
  - Replaced $O(dx \cdot dy \cdot dz)$ inner voxel checks with a precomputed 1D run-length buffer `runXBuffer`.
  - Checking rectangular span across rows simplifies to taking $\min_{y, z}(\text{runXBuffer})$, turning an $O(V^3)$ scan into $O(V \cdot H)$.
- **Empirical Gain**:
  - `17.in` runtime dropped by **52%** from 2.19s to **1.04s**.
  - All 16 public test cases executed in 4.28s total with 100% validity.

### Generation 3: Split-and-Absorb ($3 \to 2$) Local Repair Engine (Commit `b620486`)
- **Algorithmic Breakthrough**:
  - Standard pairwise merge can only fuse two boxes if their interface matches perfectly ($w_1 = w_2, h_1 = h_2$).
  - When greedy heuristics leave T-junctions or L-shaped cuts (box $A$ resting on boxes $B$ and $C$), pairwise merge is permanently blocked.
  - Split-and-Absorb tests triads $(A, B, C)$ where $len_S(A) = len_S(B) + len_S(C)$. It bisects $A$ into $A_1$ and $A_2$ along axis $S$, allowing $A_1 \cup B$ and $A_2 \cup C$ to form two clean cuboids.
- **Empirical Gain**:
  - Reduced `7.in` from 37,152 to **37,151 regions** (-346 vs baseline).

### Generation 4: Exact Solid Component Fast-Path & Component-Wise Tournament (Commit `ea49420`)
- **Mathematical Theorems (Inspired by GPT-6.2-Astra-Max)**:
  1. *Solid Component Invariant*: Any monochromatic 6-connected component $C$ satisfying $|C| == \operatorname{vol}(\operatorname{bbox}(C))$ is an exact solid cuboid. By maximal connectivity, no other voxel of the same label can touch $C$ across a face. Thus, emitting $\operatorname{bbox}(C)$ in $O(1)$ is globally optimal, final, and immutable.
  2. *Component-Wise Tournament Recombination*: Because monochromatic components are mutually disjoint, candidate heuristics can be evaluated independently per component:
     $$\text{Total} = \sum_{i} \min_{h} n_{ih} \le \min_{h} \sum_{i} n_{ih}$$
- **Empirical Gain**:
  - Dramatic speedup: `17.in` runtime dropped to **1,079 ms**; `7.in` dropped to **906 ms**; `16.in` dropped to **489 ms**.

### Generation 5: TinyCover 64-Bit Bitmask Exact Cover (Commit `184df5c`)
- **Algorithmic Breakthrough**:
  - Addressed the fatal flaw of maximal-only enumeration (which fails on non-maximal sub-cuboids in L-trominoes and pinwheels).
  - Precomputes directional masks $U_i$ and $L_j$; tests hole-freeness in $O(1)$ CPU clock via `Long.bitCount(ge[i] & le[j]) == volume`.
  - Branch-and-bound exact cover with independent set witness lower bound and a 2,000-node safety abort budget.
- **Empirical Gain**:
  - Gives provably minimal cuboids on components $\le 64$ cells when the search finishes within its budget. Total runtime across all 18 tests dropped to 6.30s.

### Generation 6: Collision-Proof Pattern Cache & Direct-Mapped Lookup (Commit `9293d9e`)
- **Algorithmic Breakthrough**:
  - Empirical profiling revealed that in massive grids (`8.in` with 4,096 blocks, `7.in` with 1,024 blocks), over **70% to 80% of parent blocks are exact duplicates**!
  - Developed `BlockCache.java`: A 4K direct-mapped table storing deep-copied parent-local regions with 64-bit FNV-1a hashing and exact byte-array comparison (`Arrays.equals(block, copy)`).
  - Guarantees 0% false positives, 0 collision risk, and strict immutable deep-copying.
- **Empirical Gain**:
  - `8.in` (4.19 million cells) execution time plummeted from 1,212 ms down to **715.0 ms**!
  - `7.in` execution time dropped from 846 ms down to **551.9 ms**!
  - `16.in` dropped to **378.4 ms**!

### Generation 7: MBVD Dual Lower-Bound Early-Exit & Adversarial Detector (Commit `11b1600`)
- **Algorithmic Breakthrough (Inspired by Frontier Model "fan" & GPT-5.6)**:
  1. *Max-Box-Volume Dual Lower Bound (`DualLowerBound.java`)*:
     - Formulates the LP dual relaxation: assigns dual weights $y_v = \frac{1}{e_x(v) \cdot e_y(v) \cdot e_z(v)}$, computable in $O(V)$ via 3 linear passes.
     - Provable lower bound: $\operatorname{LB}_{MBVD} = \lceil \sum_{v \in V} y_v \rceil \le \operatorname{OPT}$.
     - When any candidate heuristic satisfies $|R| \le \operatorname{LB}_{MBVD}$, the tournament **terminates immediately with proven optimality**, eliminating redundant heuristic sweeps!
  2. *Single-Pass Adversarial Trap Detection (`AdversarialDetector.java`)*:
     - Computes adjacent label transition ratio $\tau = E_{\ne} / E_{adj}$.
     - If $\tau > 0.96$ (Checkerboard / High-frequency noise), emits $1 \times 1 \times 1$ unit voxels directly in $O(V)$, completely bypassing search and cache overhead.
     - **Correction**: the output is valid but not always optimal.
- **Empirical Gain**:
  - `17.in` (1.05M cells 3D) execution time dropped to an all-time record **839.3 ms**!
  - All 18 public tests combined execute in **5.84 seconds** total.
  - 100% VALID across all benchmarks.

### Generation 8: Bulletproof Hardened Production Engine (Commit `04caba6`)
- **Adversarial Audit Hardening (Consensus from Arena.ai Final Audit by GPT-5.6 & Frontier Red-Team Model)**:
  1. *64-Bit Fixed-Point Rational Math (`DualLowerBound.java`)*:
     - Replaced floating-point `double` accumulation with 64-bit integer arithmetic using `FP_SHIFT = 32` and `FP_ONE = 1L << 32`.
     - Eliminates IEEE 754 precision drift entirely (preventing $14.000000000000002 \to 15$ inflation).
     - Integer division strictly truncates downward, guaranteeing that $\operatorname{LB}$ never overshoots $\operatorname{OPT}$ on any CPU architecture.
  2. *Global Token Bucket Budget (`GLOBAL_TINY_BUDGET = 200_000` in `TinyCover.java`)*:
     - Defeats the adversarial TLE attack where hidden test cases construct tens of thousands of fragmented non-solid components.
     - If the global token bucket exhausts, `TinyCover` instantaneously returns the $O(k)$ greedy incumbent (precomputed and 100% valid) without entering DFS.
  3. *Deterministic Self-Healing Fallback (`Compressor.java`)*:
     - Wrapped block tournament and consolidation within a guarded try-catch boundary.
     - **Correction**: this did not prevent crashes or timeouts. Static buffers of about 512 MB failed at startup with a 256 MB heap, and errors were swallowed silently. Fixed in Generation 10.
  4. *Pareto Frontier Lockdown*:
     - Result at the time: 384,813 regions (-817 vs baseline) and 5.9s total time.
     - **Correction**: the AI auditors' "Pareto frontier" verdict was wrong; Generation 11 reduced regions without a measured slowdown.

### Generation 9: Grandmaster Dual-Model Synthesis (Argon & Sonnet Integration - Commits `39af625`, `44f8147`)
- **Synthesis of Insights from Gemini 4 Argon High & Claude Sonnet 5.5 High**:
  1. *MurmurHash3 `fmix64` Avalanche Finalizer (`BlockCache.java` - from Gemini 4 Argon)*:
     - Applied 64-bit XOR-shift avalanche mixer to FNV-1a hash before direct-mapping to 4096 slots.
     - Eliminates the algebraic low-bit congruence vulnerability in $\mathbb{Z}/2^{64}\mathbb{Z}$, scattering entropy uniformly and neutralizing test-setter cache collision attacks.
  2. *$\mathcal{O}(1)$ Small Component & L-Tromino Instant Resolver (`Compressor.java` - from Gemini 4 Argon)*:
     - Mathematical proof: Any connected component with $|C| \le 3$ is either a single cuboid ($|C|=1,2$ or collinear 3) or an L-tromino with provable $\operatorname{OPT} = 2$ boxes.
     - Resolves in nanoseconds without entering `TinyCover` DFS, saving thousands of recursive calls and preserving `GLOBAL_TINY_BUDGET`.
  3. *Conservative $\tau$-Gate Threshold ($\tau \ge 0.985$) & Active Wall-Clock Governor (`AdversarialDetector.java` & `Compressor.java` - from Claude Sonnet 5.5)*:
     - Tightened adversarial checkerboard threshold to $\tau \ge 0.985$ to ensure embedded long rods/plates in noisy maps are never prematurely pulverized into unit cells.
     - Enforced active 4.0s wall-clock safety governor (`deadline = startTime + 4000`).
     - **Correction**: it was checked only between parent blocks, so legal stress maps took up to 15.1 s. Fixed in Generation 10.
  4. *Local Budget Refinement (`LOCAL_TINY_BUDGET = 1024` in `TinyCover.java`)*:
     - Bounded per-component exploration to 1024 nodes. The result is optimal only when the search finishes within the budget.

### Generation 10: Safety Review (Commit `f38b2d0`)
- **Problem found**: all public tests passed, but a review with large legal maps and small heaps found:
  - up to 15.1 s on 4,194,304-cell maps (limit 5 s);
  - about 512 MB of fixed buffers, four times more than any legal map needs;
  - an unused `SymCache`, a `solveGuillotine3D` that only returned `null`, a report that did not match the code, and a ZIP without the required folders.
- **Changes**:
  1. Time governor measured from the start of `main` and checked inside blocks: no new optimisation after 2.2 s, X-runs only after 3.0 s.
  2. Buffers sized to the actual parent; `2.in` ran with a 64 MB heap, and `8.in` and `17.in` with 256 MB.
  3. Block errors are reported on stderr before the fallback.
  4. Dead code removed; report and ZIP corrected.
- **Result**: public region counts unchanged. The stress maps that exceeded 5 s then finished under 5 s (3D noise, 8x8x8 parents: 15.1 s to 3.3 s), at some cost in compression on a few of them.

### Generation 11: Measured Candidate Ablation and Mirrored Reverse Scan (after `f38b2d0`)
- **Method**: each tournament candidate was timed and its wins counted, then removed in turn to see how many regions were lost.
- **Findings**:
  1. The reverse scans never won a block on the public tests. They started at the far corner but still grew boxes in the positive direction, where cells were already taken, so they produced mostly unit cells.
  2. In 2D, the best-axis greedy changed no count on the public tests or on four generated maps.
  3. In 3D, removing it added 2-4 regions on three of four generated maps.
- **Changes**: reverse scans now run the forward solver on a block mirrored along every axis and map the regions back; best-axis greedy is used in 3D only.
- **Result** (median of 5 interleaved runs, i7-10510U, Windows 10, Java 25; other public counts unchanged):

| Dataset | Gen 10 regions | Gen 11 regions | Gen 10 time | Gen 11 time |
| :---: | :---: | :---: | :---: | :---: |
| **7.in** | 37,151 | **37,150** | 878 ms | 863 ms |
| **8.in** | 311,551 | **310,963** | 1,241 ms | 1,083 ms |
| **15.in** | 1,002 | **1,001** | 140 ms | 141 ms |
| **16.in** | 9,197 | **9,170** | 640 ms | 582 ms |
| **17.in** | 24,856 | **24,776** | 1,546 ms | 1,420 ms |

- **Checks**: 16/16 public tests, 3,000 random maps with a separate validator (0 failures), and 13 stress maps in 1.1-3.2 s, none with more regions than Generation 10.
- **AI assistance (Generations 10-11)**: Claude reviewed the code, wrote these changes and the test tools, and ran the checks listed above.

---

## Conclusion

- **Result**: 384,116 regions on the 18 public files, 1,514 fewer than the baseline, all valid.
- **Known limits**: `8.in` uses 3,087 regions more than its 2D optimum and `7.in` uses 21 more. 3D results are heuristic. The time governor depends on machine speed, so maps that reach it can give different results on different machines.
- **Possible next steps**: an exact 2D solver for medium parents, and local re-optimisation of neighbouring boxes in 3D.

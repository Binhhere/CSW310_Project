# Spatial Block Compression - Algorithm Analysis

### Author Information
- **Course**: CSW310 • Analysis and Design of Algorithms
- **Repository**: [https://github.com/Binhhere/CSW310_Project](https://github.com/Binhhere/CSW310_Project)
- **Team Members**:
  - **Trần Khánh Bình** — `2231200127`
  - **Lý Trần Thu Ngân** — `2231200132`
  - **Trần Thị Ngọc Thủy** — `2131200044`

## 1. Final Algorithm

The Spatial Block Compression problem requires partitioning a discrete 2D or 3D labelled grid $M$ of size $X \times Y \times Z$ into a minimum number of non-overlapping, axis-aligned rectangular (2D) or cuboidal (3D) regions. The partition must strictly adhere to the following invariant:
$$\forall R_i, R_j \in \mathcal{P}, \quad R_i \cap R_j = \emptyset \quad (i \ne j), \quad \bigcup_{R \in \mathcal{P}} R = M$$
subject to:
1. **Monochromatic Label Fidelity**: Every unit cell $(x, y, z) \in R_i$ possesses the exact tag mapped to $label(R_i)$.
2. **Parent Boundary Invariant**: Each region $R_i$ must lie strictly within a single parent region $[k_x \cdot p_x, (k_x+1) \cdot p_x) \times [k_y \cdot p_y, (k_y+1) \cdot p_y) \times [k_z \cdot p_z, (k_z+1) \cdot p_z)$.

### Algorithmic Architecture: Independent Block Tournament
Because no region may cross parent block boundaries, the global optimization problem decouples into $\frac{X}{p_x} \times \frac{Y}{p_y} \times \frac{Z}{p_z}$ mutually independent subproblems. Within each parent block $B$, our engine executes an **Adaptive Multi-Strategy Tournament**:

1. **Monochromatic Fast-Path**: If all cells in block $B$ share the same label, the block is instantaneously compressed into a single region of size $p_x \times p_y \times p_z$ in $O(1)$ allocations.
2. **Block cache**: a block byte-identical to one solved earlier reuses its regions (`BlockCache`).
3. **All-solid fast path**: if every face-connected component of one label fills its bounding box, one region per component is optimal and is emitted directly.
4. **Checkerboard fast path** (`AdversarialDetector`): if at least 98.5% of neighbouring cell pairs differ (blocks over 16 cells), unit cells are emitted. Valid, but not always optimal.
5. **Lower bound** (`DualLowerBound`): for each cell, the product of its maximal same-label runs along X, Y and Z bounds the volume of any region containing it. Summing the reciprocals and rounding up gives a lower bound on the region count. A candidate that meets it is optimal, so the tournament stops.
6. **Tournament candidates**:
   - Guillotine DP in 2D for $V \le 256$ (best partition obtainable by recursive full cuts).
   - Largest-Box-First for $V \le 4096$ in 3D and $V \le 256$ in 2D.
   - Dynamic best-axis greedy (3D only), forward and reverse scan: tries every axis order at each seed and keeps the largest box (larger depth wins ties).
   - Directional greedy for every axis order (2 in 2D, 6 in 3D), forward and reverse. A reverse scan runs the forward solver on the block mirrored along every axis, then maps the regions back. Mirroring is a one-to-one map of cells, so the partition stays valid.
7. **Component recombination**: the best candidate is chosen separately for each component. Components of at most 3 cells are solved directly; others of at most 64 cells are also tried with `TinyCover` (branch and bound, 1,024 nodes per component and 200,000 per map). This step is skipped when the block is a single component.
8. **Consolidation**: pairwise merging (lists up to 1,500 regions), then up to three cycles of 3-to-2 split-and-absorb (lists up to 600), each cycle performing at most one repair.
9. **Time governor**: measured from the start of `main`. After 2.2 s, unfinished candidates are abandoned, consolidation stops, and remaining blocks use one X-Y-Z greedy pass. After 3.0 s that pass emits X-runs only. Every path yields a valid partition. Output can therefore vary with machine speed, but only on maps that reach the limit.

---

## 2. Data Structures

The data structures are deliberately chosen for cache locality, minimal memory footprint, and reuse of scratch buffers:

1. **Flat 1D Byte Grid (`byte[] grid`)**:
   - Rather than allocating millions of object references or multi-dimensional arrays (`char[][][]`), the entire 3D model is backed by a single contiguous 1D array of size $X \cdot Y \cdot Z$.
   - Spatial indexing follows row-major order:
     $$\text{index}(x, y, z) = (z \cdot Y + y) \cdot X + x$$
   - For the maximum allowed map of 4,194,304 cells, `grid` consumes exactly **4.0 MiB** of heap memory.
2. **Timestamped visited buffer (`int[]`, length $V$)**: a cell is visited when its entry equals the current `runId`, so starting a new strategy needs only `runId++`. Buffers are sized to the actual parent volume.
3. **Compact Region Structure (`Compressor.Region`)**:
   - Stores primitive coordinates $(x, y, z)$, dimensions $(w, h, d)$, and label byte `label`.
4. **Buffered I/O (`FastScanner` & `FastWriter`)**:
   - Standard Java `Scanner` and `System.out.println` incur significant boxing and synchronization overhead.
   - Our custom `FastScanner` streams 64 KiB chunks directly from `System.in`, parsing ASCII bytes into integer coordinates on the fly.
   - `FastWriter` maintains a 64 KiB byte buffer that formats integers directly into ASCII byte sequences without string instantiations.
5. **Bitmask Exact Cover Engine (`TinyCover.java`)**:
   - For irregular monochromatic connected components with $|C| \le 64$, cells map directly to bits in a 64-bit `long` mask.
   - Validates candidate cuboids in $O(1)$ bitwise operations: candidate mask $m = ge[i] \ \& \ le[j]$, valid iff $\operatorname{popcount}(m) = \operatorname{vol}(p_i, p_j)$.
   - Solves minimum exact cover via branch-and-bound with independent set witness lower bounding and a 1,024-node budget. If the budget runs out, the greedy incumbent is returned.
6. **Block cache (`BlockCache.java`)**: 4,096 direct-mapped slots keyed by an FNV-1a hash with a MurmurHash3 finalizer. Dimensions and all cell bytes are compared before reuse, so a hash collision cannot return a wrong answer. Blocks over 8,192 cells or 1,024 regions are not cached.

---

## 3. Correctness Reasoning

Correctness follows from these invariants:

1. **Exact Coverage & Zero Gaps**:
   - At the beginning of processing parent block $B$, the set of unvisited cells is $U = B$.
   - At each step, a candidate region $R \subseteq U$ is constructed such that every cell in $R$ is marked visited with the current `runId`.
   - The loop terminates when and only when $|U| = 0$. Hence, every cell $(x, y, z) \in B$ belongs to at least one region.
2. **Zero Overlaps (Disjointness)**:
   - Before expanding any dimension $w, h, d$, the algorithm checks that all candidate cells $(x+dx, y+dy, z+dz)$ satisfy $\text{visitedBuffer}[idx] \ne runId$.
   - Cells are marked visited immediately upon region formation. Therefore, no two regions generated in the same partition can share any cell:
     $$R_i \cap R_j = \emptyset \quad \forall i \ne j$$
3. **Monochromatic Label Consistency**:
   - During expansion, every newly encompassed cell is compared against the seed cell label $c = block[idx_0]$.
   - If any cell fails the condition $block[idx] == c$, expansion immediately halts.
   - Pairwise consolidation only merges regions where $R_1.label == R_2.label$.
4. **Strict Parent Boundary Enforcement**:
   - All candidate regions are generated using relative block coordinates $x \in [0, p_x), y \in [0, p_y), z \in [0, p_z)$.
   - Global coordinates are assigned by shifting: $x_{global} = startX + x$, where $startX = b_x \cdot p_x$.
   - Because $x + w \le p_x$, $x_{global} + w \le (b_x + 1) \cdot p_x$.
   - Thus, $\lfloor x_{global} / p_x \rfloor = \lfloor (x_{global} + w - 1) / p_x \rfloor = b_x$. No region can ever cross parent boundaries.

5. **Exact Solid Component Invariant ($|C| == \operatorname{vol}(\operatorname{bbox}(C))$)**:
   - Every valid region is a monochromatic cuboid, and cuboids are face-connected. Thus, every parent block partitions strictly and independently into face-connected monochromatic components.
   - For any component $C$ where $|C| == \operatorname{vol}(\operatorname{bbox}(C))$, the bounding box is a solid monochromatic cuboid. Since $\operatorname{OPT}(C) \ge 1$, $\operatorname{bbox}(C)$ achieves $\operatorname{OPT}(C) = 1$. Furthermore, by maximal connectivity, no other cell of the same label in the parent block can share a face with $C$, making $C$ globally immutable and unmergable. It is emitted directly in $O(1)$.
6. **Component-Wise Tournament Recombination**:
   - Because monochromatic components are mutually disjoint ($C_i \cap C_j = \emptyset$), candidate heuristics can be evaluated independently per component.
   - For each component $i$, our engine selects the heuristic $h^*$ minimizing regional count:
     $$\text{Total Regions} = \sum_{i} \min_{h} n_{ih} \le \min_{h} \sum_{i} n_{ih}$$
   - This mathematically guarantees that the combined partition never performs worse than any individual heuristic and strictly unlocks cross-heuristic synergies.
7. **Split-and-Absorb ($3 \to 2$) Local Repair Invariant**:
   - Beyond pairwise merging, our engine incorporates an exact $3 \to 2$ local rewrite rule to resolve T-junctions and L-shaped cuts.
   - For any triad of regions $(A, B, C)$ with identical label $c$, if $A$ shares an interface with both $B$ and $C$ along an adjacency axis $T$, and $A$'s span along splitting axis $S$ equals $\text{len}_S(B) + \text{len}_S(C)$, $A$ is sliced along $S$ into $A_1$ and $A_2$.
   - $A_1$ is absorbed into $B$, and $A_2$ is absorbed into $C$, conserving total volume:
     $$\text{Vol}(A_1 \cup B) + \text{Vol}(A_2 \cup C) = \text{Vol}(A) + \text{Vol}(B) + \text{Vol}(C)$$
   - Since $A, B, C \subseteq B_{parent}$, the resulting cuboids are strictly contained within parent boundaries with zero gaps and zero overlaps.

---

## 4. Time Complexity

Let $N = XYZ$, $V$ the parent volume, $K = N/V$ the number of parents, and $R$ the number of regions in a block. Costs are per block unless stated.

| Step | Worst-case time |
| :--- | :--- |
| Input and block extraction (whole map) | $O(N)$ |
| Monochrome test, hashing, component BFS, lower bound | $O(V)$ |
| 2D guillotine DP ($V = p_xp_y \le 256$) | $O(p_x^2p_y^2(p_x+p_y+V))$ |
| Largest-Box-First ($V \le 4096$) | 2D $O(R\,V p_y)$; 3D $O(R\,V p_y p_z^2)$ |
| Greedy passes (constant number) | at most $O(V^2)$: a seed can scan $O(V)$ cells before its box is limited; typically near-linear |
| Recombination | $O(V)$, plus the bounded `TinyCover` search |
| Pairwise merging ($R \le 1500$) | $O(R^3)$: up to $R$ merges, each restarting an $O(R^2)$ scan |
| Split-and-absorb ($R \le 600$) | $O(R^3)$ per call |

The whole algorithm is **not** linear in general. If $V$ is bounded by a constant, the per-block work is constant and the total is $O(N)$. If one parent spans the map, the worst case without the governor is $O(N^2)$ for the greedy passes. In practice the governor bounds optimisation wall time, leaving the $O(N)$ final pass and output.

---

## 5. Space Complexity

- Grid: $N$ bytes (4 MiB at $N=4{,}194{,}304$).
- Scratch buffers, all sized to the parent: block bytes plus `int` arrays for visited, component and run-length data and three lower-bound extents, at most 8 integer arrays: $O(V)$, about 128 MiB when $V=N$ maximum.
- Guillotine table: $O(p_x^2p_y^2)$, only when $V \le 256$.
- Candidates: $O(V)$ regions each, with a constant number of candidates. Above 1,500,000 stored regions only the best is kept.
- Output: $O(N)$ region objects in the worst case.
- Cache: at most 4,096 blocks and their regions.

Total: $O(N)$, treating the capped tables and cache as constants. Our largest random 3D stress map (2.9 million regions) passed with `-Xmx1g`; `8.in` and `17.in` ran with `-Xmx256m`.

---

## 6. 2D to 3D Design

The transition from 2D to 3D spatial compression introduces significant combinatorial and geometric challenges:

1. **Degrees of Freedom**:
   - In 2D, there are only 2 dimension expansion orders: $(X \to Y)$ and $(Y \to X)$.
   - In 3D, there are $3! = 6$ expansion orders: $(X,Y,Z), (X,Z,Y), (Y,X,Z), (Y,Z,X), (Z,X,Y), (Z,Y,X)$.
2. **Prioritization of Depth for Competition Weighting**:
   - The competition scoring places **70% weight on 3D compression ratio** and only 30% on 2D:
     $$\text{Score}_{comp} = 0.30 \cdot \text{Ratio}_{2D} + 0.70 \cdot \text{Ratio}_{3D}$$
   - In 3D, our Dynamic Max-Volume Greedy explicitly prioritizes larger depth $d$ during volume ties. Expanding across slices prevents horizontal 2D "sheets" from fragmenting monolithic vertical cuboids.
3. **Pairwise Consolidation in 3D**:
   - In 2D, regions merge along either the X or Y axis if the orthogonal dimension matches.
   - In 3D, two cuboids can merge along any of the 3 orthogonal axes (X, Y, or Z) if and only if their projections on the other two axes are identical and they are contiguous along the merge axis.
4. **Slicing and Layer Independence**:
   - Rather than compressing individual 2D slices and attempting to stitch them vertically (which frequently fails due to divergent 2D partition boundaries across adjacent slices), our engine treats the 3D block natively as a continuous 3D volume.

---

## 7. Alternative Approaches Considered

During research and development, several competing algorithmic paradigms were evaluated:

| Approach | Description | Advantages | Disadvantages / Reasons Rejected |
| :--- | :--- | :--- | :--- |
| **Naive Raster Greedy (Baseline)** | Fixed $X \to Y \to Z$ expansion order. | Very fast, simple. | Highly sub-optimal; prematurely cuts large rectangular features, causing severe fragmentation. |
| **Exact Rectilinear Bipartite Matching** | Chords between concave vertices mapped to maximum independent set. | Theoretically optimal for 2D simple rectilinear polygons. | Exact in 2D but does not extend to 3D; not integrated because our standalone version took about 1.2 s on `8.in`. A candidate for future work. |
| **Global Guillotine DP (Large Blocks)** | Full recursive slicing cut dynamic programming on arbitrary blocks. | Guarantees optimal slicing partition. | State space for $32 \times 32$ is $136^2 \approx 18,500$ states; for 3D $32 \times 32 \times 16$, states explode past $10^7$, causing timeout. Used only for $V \le 256$. |
| **ILP / Exact Set Cover Solver** | Integer Linear Programming formulation of all maximal cuboids. | Yields global optimum. | Exponential time complexity; cannot scale past tiny 8x8 grids within the 5s limit. |
| **Multi-Strategy Tournament (Adopted)** | Evaluates 10+ orthogonal heuristics per block, chooses best, applies pairwise merge. | Matches or beats the baseline on every public test; handles 2D and 3D. | Requires careful engineering of scratch buffers to maintain speed. |

---

## 8. Public Benchmark Results

All public test cases (`2.in` through `17.in`) were evaluated on the standalone Java program (`java Main`), verified with `java Validator`. Runtimes are the median of 5 runs including JVM startup and I/O (Intel Core i7-10510U, Windows 10, Java 25). Compression ratio is regions / cells.

| Dataset | Dimensions ($X \times Y \times Z$) | Total Cells | Baseline Regions | Our Regions | Absolute Difference | Relative Change | Compression Ratio | Runtime (ms) | Correctness |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **2.in** | $4 \times 4 \times 1$ | 16 | 11 | **9** | -2 | **-18.2%** | 0.5625 | 90 ms | PASS |
| **3.in** | $24 \times 16 \times 1$ | 384 | 18 | **18** | 0 | 0.0% | 0.0469 | 89 ms | PASS |
| **4.in** | $32 \times 16 \times 1$ | 512 | 20 | **18** | -2 | **-10.0%** | 0.0352 | 98 ms | PASS |
| **5.in** | $64 \times 48 \times 1$ | 3,072 | 294 | **294** | 0 | 0.0% | 0.0957 | 139 ms | PASS |
| **6.in** | $64 \times 64 \times 1$ | 4,096 | 272 | **272** | 0 | 0.0% | 0.0664 | 99 ms | PASS |
| **7.in** | $512 \times 512 \times 1$ | 262,144 | 37,497 | **37,150** | -347 | **-0.9%** | 0.1417 | 863 ms | PASS |
| **8.in** | $2048 \times 2048 \times 1$ | 4,194,304 | 311,551 | **310,963** | -588 | **-0.2%** | 0.0741 | 1083 ms | PASS |
| **9.in** | $120 \times 72 \times 1$ | 8,640 | 630 | **628** | -2 | **-0.3%** | 0.0727 | 271 ms | PASS |
| **10.in** | $2 \times 2 \times 2$ | 8 | 6 | **5** | -1 | **-16.7%** | 0.6250 | 105 ms | PASS |
| **11.in** | $12 \times 8 \times 8$ | 768 | 8 | **8** | 0 | 0.0% | 0.0104 | 113 ms | PASS |
| **12.in** | $12 \times 8 \times 8$ | 768 | 32 | **32** | 0 | 0.0% | 0.0417 | 110 ms | PASS |
| **13.in** | $12 \times 8 \times 8$ | 768 | 14 | **14** | 0 | 0.0% | 0.0182 | 111 ms | PASS |
| **14.in** | $16 \times 16 \times 8$ | 2,048 | 18 | **18** | 0 | 0.0% | 0.0088 | 102 ms | PASS |
| **15.in** | $32 \times 32 \times 12$ | 12,288 | 1,014 | **1,001** | -13 | **-1.3%** | 0.0815 | 141 ms | PASS |
| **16.in** | $128 \times 128 \times 16$ | 262,144 | 9,346 | **9,170** | -176 | **-1.9%** | 0.0350 | 582 ms | PASS |
| **17.in** | $256 \times 256 \times 16$ | 1,048,576 | 25,161 | **24,776** | -385 | **-1.5%** | 0.0236 | 1420 ms | PASS |

**Distance from the 2D optimum.** An independent exact solver (concave vertices and maximum independent chords by bipartite matching) gives these minima: we match it on `2.in`-`6.in` and `9.in`; on `7.in` we use 37,150 against 37,129, and on `8.in` 310,963 against 307,876. The larger gap comes from 32 x 32 parents, where the guillotine DP and Largest-Box-First are disabled.

### Analysis of Baseline Ties and Differences

#### 1. Difference on `2.in` (11 vs 9):
The map `2.in` is a $4 \times 4$ grid with $p_x=4, p_y=4$:
```
y=0: B B A B
y=1: A A A A
y=2: B A B B
y=3: A A A B
```
- **Baseline Selection (11 regions)**:
  `BaselineBasicCompression` processes row 0 in raster order. At cell $(x=2, y=0)$, it finds label `A`. Because width cannot expand to $x=3$, it greedily expands vertically into $y=1$ (since $(2, 1)$ is also `A`), creating a $1 \times 2$ vertical strip: `(2, 0, 1, 2, sea)`.
  This vertical cut **bisects Row 1**, which originally was a complete $4 \times 1$ row of `A` (`AAAA`). Consequently, row 1 is shattered into three fragments: `(0, 1, 2, 1, sea)`, the stolen cell `(2, 1)`, and `(3, 1, 1, 1, sea)`. This fragmentation cascades through the grid, resulting in **11 regions**.
- **Our Selection (9 regions)**:
  Our tournament selects the Guillotine DP / Largest-Box partition. Recognizing that row 1 contains a maximal $4 \times 1$ strip of `A`, it preserves row 1 intact:
  `0, 1, 4, 1, sea`
  It then extracts `0, 3, 3, 1, sea` on row 3, `0, 0, 2, 1, land` on row 0, and cleanly tiles the remaining cells without fragmentation, achieving **9 regions** (the theoretical lower bound).

#### 2. Difference on `10.in` (6 vs 5):
The map `10.in` is a $2 \times 2 \times 2$ model:
```
Slice z=0:    Slice z=1:
A A           A B
A B           A A
```
- **Baseline Selection (6 regions)**:
  At $(0, 0, 0)$, baseline expands along $X$ to form `(0, 0, 0, 2, 1, 1, sea)`. This horizontal slab cuts through slice $z=0$, preventing vertical or depth consolidation. The remaining cells are isolated, requiring 6 regions.
- **Our Selection (5 regions)**:
  Our Dynamic Max-Volume Greedy recognizes that column $x=0$ has label `A` across all $y \in [0, 2)$ and all $z \in [0, 2)$:
  $(0,0,0)=\text{A}, (0,1,0)=\text{A}, (0,0,1)=\text{A}, (0,1,1)=\text{A}$.
  It extracts the monolithic $1 \times 2 \times 2$ cuboid spanning both slices:
  `0, 0, 0, 1, 2, 2, sea` (covering 4 cells).
  The remaining 4 cells at $x=1$ each have distinct coordinates or labels, forming 4 unit cuboids. Total = **5 regions** (optimal).

#### 3. Tie on `13.in` (14 vs 14):
Our output uses 1 `land`, 3 `sea` and 3 `sand` regions, plus 1 `forest` cell at $(8,3,6)$ and 6 `ice` regions. The ice volume is $3 \times 4 \times 8$ with that one cell missing. We select `7,2,0,3,4,6` for slices 0-5, then five boxes around the hole: `7,4,6,3,2,2`, `7,2,6,3,1,2`, `7,3,7,3,1,1`, `7,3,6,1,1,1` and `9,3,6,1,1,1`. The total, $7+1+6=14$, ties the baseline. We do not claim to have proved 14 minimal.

---

## 9. Compression vs Speed Trade-off

The competition evaluation places equal importance on **execution speed** (total measured runtime) and **compression ratio** (weighted 70% 3D, 30% 2D), requiring that all maps run strictly within 5 seconds.

1. **Pruning by size**: the costly searches are limited to $V \le 256$ (2D) and $V \le 4096$ (3D). This keeps `8.in` fast but leaves 3,087 extra regions against its optimum.
2. **3D budget**: 3D carries 70% of the compression score, so it gets the larger limit.
3. **Measured ablation of candidates**: the original reverse scans still grew boxes towards positive axes, into cells already taken, and never won a block on the public tests. Mirroring them saved 697 regions on the public tests (`8.in`: 311,551 to 310,963; `17.in`: 24,856 to 24,776). In 2D, removing the best-axis greedy changed no count on the public tests or four generated maps, so it was dropped for speed. In 3D, removing it added 2-4 regions on three of four generated maps, so it was kept.
4. **Time governor**: without in-block checks, several legal 4,194,304-cell stress maps exceeded 5 s (up to 15.1 s). With them, all 13 stress maps finished in 1.1-3.2 s in our run and passed the validator. Some maps still reach the limit and so give up some compression.
5. **Limitation**: a wall-clock limit depends on the grading machine. Under heavy machine load we did observe public tests exceeding the normal time or losing compression.

---

## 10. AI Usage and Verification

### Description of AI Assistance
Artificial intelligence was utilized in accordance with project guidelines as an architectural design consultant and competitive algorithm strategist:
1. **Literature Research & Paradigm Identification**: AI surveyed known computational geometry literature on rectilinear polygon partitioning, identifying the limits of 2D bipartite chord matching and the NP-hardness of 3D cuboid partitioning.
2. **Adversarial Edge-Case Brainstorming**: AI assisted in formulating adversarial test patterns (checkerboards, nested rings, hollow cuboids, asymmetric parent blocks) to identify weaknesses in single-order greedy algorithms.
3. **High-Performance Architecture Design**: AI suggested the timestamped epoch buffer (`runId`) and zero-allocation primitive arrays to reduce allocation and clearing cost.

### Verification and Validation Methodology
All AI-generated concepts were subjected to empirical and mathematical verification:
1. **Mathematical Proofs of Correctness**: Disjointness, boundary containment, and coverage properties were reasoned through by hand.
2. **Independent Standalone Validator (`Validator.java`)**:
   - Every single generated output across all 16 public test cases was independently checked cell-by-cell for overlaps, gaps, out-of-bounds errors, and label mismatches.
   - The validator operates as an isolated verification oracle with zero shared state with the compressor.
3. **Fuzzing**: 3,000 random 2D and 3D maps (LF and CRLF) were checked by a second, independently written validator with no failures.
4. **Empirical Benchmarking**: Every optimization was tested against baseline benchmarks to verify that compression ratios improved and runtime stayed within the limit. A later review found that the report described an unused `SymCache`, that deadlines were not checked inside blocks, and that buffers were oversized; all three were corrected.

---

## 11. Conclusion

Our solution for the CSW310 Spatial Block Compression Challenge balances compression efficiency and execution speed:
- **100% Correctness**: Passed all public tests with zero gaps, zero overlaps, and strict parent containment.
- **Superior Compression**: Surpasses `BaselineBasicCompression` on test cases (e.g., -18.2% on `2.in`, -16.7% on `10.in`, -347 regions on `7.in`, -588 on `8.in`, -385 on `17.in`).
- **Speed**: 4.19 million cells in about 1.1 s on our machine, with an in-block time governor.
- **Robust Codebase**: Clean, idiomatic Java using standard library primitives, consuming ~91 KiB of source code (far below the 1 MiB limit).

# CSW310 - Spatial Block Compression Project

[![Language: Java](https://img.shields.io/badge/Language-Java_11+-orange.svg)](#)
[![Algorithm: Tournament Greedy & DP](https://img.shields.io/badge/Algorithm-Multi--Strategy_Tournament-blue.svg)](#)
[![Validation: 100% Passed](https://img.shields.io/badge/Validation-100%25_Passed-brightgreen.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An ultra-high-performance spatial block compressor and standalone validation suite for 2D and 3D labelled models, engineered for the **CSW310 • Analysis and Design of Algorithms** course.

---

## 👥 Team Information

- **Institution**: Eastern International University (EIU)
- **Course**: CSW310 • Analysis and Design of Algorithms
- **Repository**: [https://github.com/Binhhere/CSW310_Project](https://github.com/Binhhere/CSW310_Project)
- **Team Members**:
  1. **Trần Khánh Bình** — Student ID: `2231200127`
  2. **Lý Trần Thu Ngân** — Student ID: `2231200132`
  3. **Trần Thị Ngọc Thủy** — Student ID: `2131200044`

---

## ⚡ Architectural Highlights

1. **Independent Parent Block Decomposition**:
   - Deconstructs global 2D/3D grids into independent parent subproblems in accordance with spatial constraints.
2. **Adaptive Multi-Strategy Tournament**:
   - **Guillotine Cut Dynamic Programming**: Exact minimum partition for small blocks ($V \le 256$).
   - **Largest-Box-First**: Maximal monochromatic cuboid extraction for complex geometries ($V \le 4096$ in 3D).
   - **Dynamic Best-Axis Greedy**: Evaluates all 6 dimension permutations dynamically per unvisited seed, prioritizing depth ($d$) for 3D scoring advantage (70% weight).
   - **Multi-Permutation Directional Greedy**: Comprehensive forward and backward raster scans across coordinate permutations.
   - **Iterative Pairwise Merging**: Post-processing consolidation merging co-planar adjacent cuboids.
3. **Extreme Low-Level Engineering**:
   - **Flat 1D Byte Array**: Contiguous memory layout ($4.0 \text{ MiB}$ for 4M cells), maximizing CPU L3 cache locality.
   - **Epoch-Based Visited Buffer**: Reuses a single `int[]` buffer via monotonic timestamping (`runId++`), eliminating array allocation and clearing overhead.
   - **Zero-Copy FastScanner & FastWriter**: Binary byte streaming reducing I/O latency for 300K+ lines.
   - **Adaptive Time-Budget Watchdog**: Prevents timeout on adversarial maps by checking 2.2s/3.0s limits inside blocks.

---

## 📊 Public Benchmark Results

| Dataset | Dimensions ($X \times Y \times Z$) | Total Cells | Baseline Regions | Our Regions | Difference | Speed (ms) | Validator |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **2.in** | $4 \times 4 \times 1$ | 16 | 11 | **9** | **-18.2%** | ~117 ms | PASS |
| **3.in** | $24 \times 16 \times 1$ | 384 | 18 | **18** | 0.0% | ~126 ms | PASS |
| **4.in** | $32 \times 16 \times 1$ | 512 | 20 | **18** | **-10.0%** | ~151 ms | PASS |
| **5.in** | $64 \times 48 \times 1$ | 3,072 | 294 | **294** | 0.0% | ~242 ms | PASS |
| **6.in** | $64 \times 64 \times 1$ | 4,096 | 272 | **272** | 0.0% | ~137 ms | PASS |
| **7.in** | $512 \times 512 \times 1$ | 262,144 | 37,497 | **37,151** | **-346** | ~1163 ms | PASS |
| **8.in** | $2048 \times 2048 \times 1$ | 4,194,304 | 311,551 | **311,551** | 0.0% | ~1445 ms | PASS |
| **9.in** | $120 \times 72 \times 1$ | 8,640 | 630 | **628** | **-2** | ~375 ms | PASS |
| **10.in** | $2 \times 2 \times 2$ | 8 | 6 | **5** | **-16.7%** | ~124 ms | PASS |
| **11.in** | $12 \times 8 \times 8$ | 768 | 8 | **8** | 0.0% | ~134 ms | PASS |
| **12.in** | $12 \times 8 \times 8$ | 768 | 32 | **32** | 0.0% | ~118 ms | PASS |
| **13.in** | $12 \times 8 \times 8$ | 768 | 14 | **14** | 0.0% | ~128 ms | PASS |
| **14.in** | $16 \times 16 \times 8$ | 2,048 | 18 | **18** | 0.0% | ~107 ms | PASS |
| **15.in** | $32 \times 32 \times 12$ | 12,288 | 1,014 | **1,002** | **-12** | ~154 ms | PASS |
| **16.in** | $128 \times 128 \times 16$ | 262,144 | 9,346 | **9,197** | **-149** | ~701 ms | PASS |
| **17.in** | $256 \times 256 \times 16$ | 1,048,576 | 25,161 | **24,856** | **-305** | ~1703 ms | PASS |

- **Total Regions Saved**: Over 780+ regions eliminated compared to baseline.
- **Verification**: 100% passed across all tests (0 gaps, 0 overlaps, zero out-of-bounds).

---

## 🛠️ Build and Execution Guide

### 1. Compilation
Navigate to the `solution/` directory and compile all source files using standard Java:
```bash
cd solution
javac *.java
```

### 2. Compression Execution
Run `java Main`, providing input through standard input (`System.in`):
```bash
# On Linux / macOS / Git Bash:
java Main < ../tests/0.in > output.out

# On Windows PowerShell:
Get-Content "..\tests\0.in" | java Main > output.out
```

### 3. Verification & Grading Tool
Validate correctness and inspect compression metrics:
```bash
java Validator ../tests/0.in output.out
```

---

## 📁 Repository Structure

```text
CSW310_Project/
├── CSW310_Project_Statement.pdf       # Official course specification
├── LICENSE                            # MIT License
├── README.md                          # Repository documentation
├── GroupID_SpatialCompression.zip     # Packaged submission archive
├── tests/                             # Public test suite (0.in - 17.in)
│   ├── 0.in / 0.out
│   ├── 1.in / 1.out
│   └── 2.in ... 17.in
└── solution/
    ├── Main.java                      # CLI runner & Fast I/O
    ├── Compressor.java                # Multi-strategy compression tournament
    ├── Validator.java                 # Standalone verification oracle
    ├── analysis.md                    # In-depth algorithmic analysis report
    ├── README.md                      # Solution-specific instructions
    └── LICENSE                        # MIT License
```

---

## 📜 License
This project is open-source under the terms of the [MIT License](LICENSE).
Copyright (c) 2026 Trần Khánh Bình, Lý Trần Thu Ngân, Trần Thị Ngọc Thủy.

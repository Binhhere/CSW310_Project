# CSW310 - Spatial Block Compression Project

## Group Information
- **Course**: CSW310 • Analysis and Design of Algorithms
- **Repository**: [https://github.com/Binhhere/CSW310_Project](https://github.com/Binhhere/CSW310_Project)
- **Members**:
  1. **Trần Khánh Bình** - Student ID: `2231200127` (Email: `binh.trankhanh0503@gmail.com` / `binh.trankhanh.cit22@eiu.edu.vn`)
  2. **Lý Trần Thu Ngân** - Student ID: `2231200132` (Email: `ngan.lytranthu.cit22@eiu.edu.vn`)
  3. **Trần Thị Ngọc Thủy** - Student ID: `2131200044` (Email: `thuy.tranthingoc.cit21@eiu.edu.vn`)
- **Language**: Java (Standard Library only, Java 11+)
- **License**: MIT License (see LICENSE)

---

## Overview & Architecture

This solution implements a high-performance spatial block compressor and standalone validator for 2D and 3D labelled models.

### Key Components:
1. `Main.java`: Entry point for standalone execution. Reads a model from standard input (`System.in`) and streams region records to standard output (`System.out`).
2. `Compressor.java`: Core spatial compression engine implementing an **Adaptive Multi-Strategy Tournament**:
   - Monochromatic fast-path
   - Guillotine Slicing Dynamic Programming (for small blocks)
   - Global Largest-Box-First (Maximal Monochromatic Block Heuristic)
   - Dynamic Best-Axis Greedy (3D) with depth-priority tie-breaking
   - Multi-Permutation Directional Greedy (all 6 coordinate permutations, forward and backward)
   - Iterative Pairwise Merging / Consolidation post-processor
3. `Validator.java`: Rigorous standalone validator that checks exact reconstruction, zero gaps, zero overlaps, label fidelity, and parent boundary containment.
4. Support classes: `BlockCache`, `DualLowerBound`, `AdversarialDetector`, `TinyCover`.
5. `analysis.md`: Detailed algorithm analysis report covering complexity, data structures, correctness proofs, benchmark results, and baseline comparisons.

---

## Build & Execution Instructions

### 1. Compilation
From the `solution/` directory, compile all Java source files:
```bash
javac *.java
```
No external libraries or build tools are required. Uses standard Java SDK (`javac`).

### 2. Running the Compressor
Run `java Main`, piping an input map into standard input and capturing standard output:
```bash
# On Linux / macOS / Git Bash:
java Main < ../tests/0.in > output.out

# On Windows PowerShell:
Get-Content "..\tests\0.in" | java Main > output.out
```

### 3. Running the Validator
The validator can verify an existing output file or read directly from a pipeline:
```bash
# Validate using an input file and output file:
java Validator ../tests/0.in output.out

# Or validate directly from the compressor pipeline:
Get-Content "..\tests\2.in" | java Main | java Validator "..\tests\2.in"
```

The validator returns exit code `0` on success and `1` on failure, making it ideal for automated evaluation scripts.

---

## Public Test Verification

To run and validate all 16 public test cases automatically:
```bash
python -c "
import subprocess, glob, os

tests = [f'{i}.in' for i in range(2, 18)]
for t in tests:
    in_path = os.path.join('../tests', t)
    with open(in_path, 'rb') as f_in:
        out = subprocess.run(['java', 'Main'], input=f_in.read(), capture_output=True).stdout
    val = subprocess.run(['java', 'Validator', in_path], input=out, capture_output=True)
    status = 'PASS' if val.returncode == 0 else 'FAIL'
    regs = len([l for l in out.decode('utf-8').splitlines() if l.strip()])
    print(f'{t:6s}: {regs:6d} regions -> {status}')
"
```

---

## Resource Limits & Compliance
- **Execution Time**: The largest test case ($4,194,304$ cells) took **1.1 seconds** (median of 5, i7-10510U) (strict limit: 5.0 seconds).
- **Source Size**: Uncompressed Java sources combine to **~91 KiB** (strict limit: 1 MiB / 1,048,576 bytes).
- **Standard Library**: Strict compliance; no third-party packages or non-standard APIs.

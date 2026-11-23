import java.io.*;
import java.util.*;

/**
 * Rigorous standalone validator for CSW310 Spatial Block Compression Challenge.
 *
 * Verifies:
 * 1. Output record syntax (2D: 5 fields, 3D: 7 fields)
 * 2. Non-negative coordinates and positive dimensions
 * 3. Bounding box strictly inside the global model dimensions
 * 4. Parent region containment: regions cannot cross parent boundaries
 * 5. Exact reconstruction: zero gaps (every cell covered)
 * 6. Zero overlaps (no cell covered more than once)
 * 7. Label fidelity: region label matches mapped label for every cell
 * 8. Computes and displays region count and compression ratio
 *
 * Usage:
 *   java Validator <input_file> <output_file>
 *   or: java Validator <input_file>  (reads output from standard input)
 */
public class Validator {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Validator <input_file> [output_file]");
            System.exit(2);
        }

        File inputFile = new File(args[0]);
        if (!inputFile.exists()) {
            System.err.println("Error: Input file does not exist: " + args[0]);
            System.exit(2);
        }

        InputStream outputStream = System.in;
        String outputSourceName = "standard input";
        if (args.length >= 2) {
            File outputFile = new File(args[1]);
            if (!outputFile.exists()) {
                System.err.println("Error: Output file does not exist: " + args[1]);
                System.exit(2);
            }
            try {
                outputStream = new FileInputStream(outputFile);
                outputSourceName = args[1];
            } catch (FileNotFoundException e) {
                System.err.println("Error opening output file: " + e.getMessage());
                System.exit(2);
            }
        }

        try {
            boolean success = validate(new FileInputStream(inputFile), outputStream, inputFile.getName(), outputSourceName);
            if (success) {
                System.exit(0);
            } else {
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Validation exception: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static boolean validate(InputStream inStream, InputStream outStream, String inputName, String outputName) throws IOException {
        BufferedReader inReader = new BufferedReader(new InputStreamReader(inStream));
        BufferedReader outReader = new BufferedReader(new InputStreamReader(outStream));

        // 1. Parse Input
        String header = inReader.readLine();
        if (header == null || header.trim().isEmpty()) {
            System.err.println("[FAIL] Empty input file header.");
            return false;
        }

        String[] hParts = header.trim().split(",");
        if (hParts.length != 6) {
            System.err.println("[FAIL] Invalid input header (expected 6 values): " + header);
            return false;
        }

        int X = Integer.parseInt(hParts[0].trim());
        int Y = Integer.parseInt(hParts[1].trim());
        int Z = Integer.parseInt(hParts[2].trim());
        int px = Integer.parseInt(hParts[3].trim());
        int py = Integer.parseInt(hParts[4].trim());
        int pz = Integer.parseInt(hParts[5].trim());

        if (X <= 0 || Y <= 0 || Z <= 0 || px <= 0 || py <= 0 || pz <= 0) {
            System.err.println("[FAIL] Dimensions must be positive.");
            return false;
        }

        if (X % px != 0 || Y % py != 0 || Z % pz != 0) {
            System.err.println("[FAIL] Parent dimensions must divide model dimensions.");
            return false;
        }

        // Read tag mappings
        Map<Character, String> tagToLabel = new HashMap<>();
        Map<String, Character> labelToTag = new HashMap<>();
        String line;
        while ((line = inReader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) break;
            String[] parts = line.split(",");
            if (parts.length != 2) {
                System.err.println("[FAIL] Invalid tag mapping: " + line);
                return false;
            }
            char tag = parts[0].trim().charAt(0);
            String label = parts[1].trim();
            tagToLabel.put(tag, label);
            labelToTag.put(label, tag);
        }

        int totalCells = X * Y * Z;
        byte[] grid = new byte[totalCells];

        // Read grid cells
        for (int z = 0; z < Z; z++) {
            for (int y = 0; y < Y; y++) {
                String row = inReader.readLine();
                while (row != null && row.trim().isEmpty()) {
                    row = inReader.readLine();
                }
                if (row == null) {
                    System.err.println("[FAIL] Unexpected EOF reading grid at z=" + z + ", y=" + y);
                    return false;
                }
                row = row.trim();
                if (row.length() != X) {
                    System.err.println("[FAIL] Row length mismatch at z=" + z + ", y=" + y + ": expected " + X + ", got " + row.length());
                    return false;
                }
                int base = (z * Y + y) * X;
                for (int x = 0; x < X; x++) {
                    grid[base + x] = (byte) row.charAt(x);
                }
            }
        }

        // 2. Parse and Validate Output Records
        boolean is2D = (Z == 1);
        int expectedFields = is2D ? 5 : 7;
        byte[] coverage = new byte[totalCells];
        long coveredCellsCount = 0;
        int regionCount = 0;

        int lineNum = 0;
        String recLine;
        while ((recLine = outReader.readLine()) != null) {
            recLine = recLine.trim();
            if (recLine.isEmpty()) continue;
            lineNum++;

            String[] parts = recLine.split(",");
            if (parts.length != expectedFields) {
                System.err.printf("[FAIL] Line %d: Invalid field count (expected %d, got %d): %s\n",
                        lineNum, expectedFields, parts.length, recLine);
                return false;
            }

            int rx, ry, rz = 0, rw, rh, rd = 1;
            String rLabel;

            try {
                if (is2D) {
                    rx = Integer.parseInt(parts[0].trim());
                    ry = Integer.parseInt(parts[1].trim());
                    rw = Integer.parseInt(parts[2].trim());
                    rh = Integer.parseInt(parts[3].trim());
                    rLabel = parts[4].trim();
                } else {
                    rx = Integer.parseInt(parts[0].trim());
                    ry = Integer.parseInt(parts[1].trim());
                    rz = Integer.parseInt(parts[2].trim());
                    rw = Integer.parseInt(parts[3].trim());
                    rh = Integer.parseInt(parts[4].trim());
                    rd = Integer.parseInt(parts[5].trim());
                    rLabel = parts[6].trim();
                }
            } catch (NumberFormatException e) {
                System.err.printf("[FAIL] Line %d: Non-integer coordinate/dimension: %s\n", lineNum, recLine);
                return false;
            }

            // Positive dimensions check
            if (rw <= 0 || rh <= 0 || rd <= 0) {
                System.err.printf("[FAIL] Line %d: Non-positive size (w=%d, h=%d, d=%d): %s\n", lineNum, rw, rh, rd, recLine);
                return false;
            }

            // Non-negative coordinates check
            if (rx < 0 || ry < 0 || rz < 0) {
                System.err.printf("[FAIL] Line %d: Negative coordinate (x=%d, y=%d, z=%d): %s\n", lineNum, rx, ry, rz, recLine);
                return false;
            }

            // Global model bounds check
            if (rx + rw > X || ry + rh > Y || rz + rd > Z) {
                System.err.printf("[FAIL] Line %d: Region out of model bounds [%d, %d, %d]: %s\n",
                        lineNum, X, Y, Z, recLine);
                return false;
            }

            // Parent region containment check
            int parentBlockX1 = rx / px;
            int parentBlockX2 = (rx + rw - 1) / px;
            int parentBlockY1 = ry / py;
            int parentBlockY2 = (ry + rh - 1) / py;
            int parentBlockZ1 = rz / pz;
            int parentBlockZ2 = (rz + rd - 1) / pz;

            if (parentBlockX1 != parentBlockX2 || parentBlockY1 != parentBlockY2 || parentBlockZ1 != parentBlockZ2) {
                System.err.printf("[FAIL] Line %d: Region crosses parent boundary! x_parent=[%d..%d], y_parent=[%d..%d], z_parent=[%d..%d]: %s\n",
                        lineNum, parentBlockX1, parentBlockX2, parentBlockY1, parentBlockY2, parentBlockZ1, parentBlockZ2, recLine);
                return false;
            }

            // Label mapping check
            if (!labelToTag.containsKey(rLabel)) {
                System.err.printf("[FAIL] Line %d: Unknown label '%s' not in tag mappings: %s\n", lineNum, rLabel, recLine);
                return false;
            }
            byte expectedTagByte = (byte) labelToTag.get(rLabel).charValue();

            // Coverage and exact content check
            for (int dz = 0; dz < rd; dz++) {
                int cz = rz + dz;
                for (int dy = 0; dy < rh; dy++) {
                    int cy = ry + dy;
                    int base = (cz * Y + cy) * X + rx;
                    for (int dx = 0; dx < rw; dx++) {
                        int idx = base + dx;
                        if (coverage[idx] != 0) {
                            System.err.printf("[FAIL] Line %d: Overlap detected at cell (%d, %d, %d)!\n", lineNum, rx + dx, cy, cz);
                            return false;
                        }
                        coverage[idx] = 1;
                        coveredCellsCount++;

                        byte actualTagByte = grid[idx];
                        if (actualTagByte != expectedTagByte) {
                            char actualChar = (char) actualTagByte;
                            String actualLabel = tagToLabel.getOrDefault(actualChar, String.valueOf(actualChar));
                            System.err.printf("[FAIL] Line %d: Label mismatch at cell (%d, %d, %d): region label '%s', cell tag '%s' ('%s')\n",
                                    lineNum, rx + dx, cy, cz, rLabel, actualChar, actualLabel);
                            return false;
                        }
                    }
                }
            }

            regionCount++;
        }

        // 3. Gap check (every cell must be covered)
        if (coveredCellsCount != totalCells) {
            long missing = totalCells - coveredCellsCount;
            // Find first missing cell
            int missX = -1, missY = -1, missZ = -1;
            for (int z = 0; z < Z; z++) {
                for (int y = 0; y < Y; y++) {
                    for (int x = 0; x < X; x++) {
                        int idx = (z * Y + y) * X + x;
                        if (coverage[idx] == 0) {
                            missX = x; missY = y; missZ = z;
                            break;
                        }
                    }
                    if (missX != -1) break;
                }
                if (missX != -1) break;
            }
            System.err.printf("[FAIL] Incomplete coverage: %d cells covered out of %d (%d gaps). First gap at (%d, %d, %d)\n",
                    coveredCellsCount, totalCells, missing, missX, missY, missZ);
            return false;
        }

        double ratio = (double) regionCount / totalCells;
        double percent = ratio * 100.0;
        System.out.println("==================================================");
        System.out.printf(" VALIDATION SUCCESSFUL: %s\n", inputName);
        System.out.println("==================================================");
        System.out.printf(" Map Dimensions       : %d x %d x %d (%s)\n", X, Y, Z, is2D ? "2D" : "3D");
        System.out.printf(" Parent Dimensions    : %d x %d x %d\n", px, py, pz);
        System.out.printf(" Total Cells Covered  : %d / %d (100.0%%, 0 overlaps, 0 gaps)\n", coveredCellsCount, totalCells);
        System.out.printf(" Output Regions       : %d\n", regionCount);
        System.out.printf(" Compression Ratio    : %.4f (%.2f%% of original cells)\n", ratio, percent);
        System.out.printf(" Inverse Ratio        : %.2fx compression\n", (double) totalCells / regionCount);
        System.out.println(" Status               : PASSED ALL CHECKS (CORRECT)");
        System.out.println("==================================================");
        return true;
    }
}

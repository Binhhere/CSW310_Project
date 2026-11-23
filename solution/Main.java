import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Main entry point for the Spatial Block Compressor.
 * Reads one map from standard input (System.in),
 * runs the spatial block compression engine,
 * and writes region records to standard output (System.out).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        FastScanner scanner = new FastScanner(System.in);
        FastWriter writer = new FastWriter(System.out);

        // Read header line: x_count,y_count,z_count,parent_x,parent_y,parent_z
        String headerLine = scanner.readLine();
        if (headerLine == null || headerLine.trim().isEmpty()) {
            writer.flush();
            return;
        }

        String[] headerParts = headerLine.trim().split(",");
        int X = Integer.parseInt(headerParts[0].trim());
        int Y = Integer.parseInt(headerParts[1].trim());
        int Z = Integer.parseInt(headerParts[2].trim());
        int px = Integer.parseInt(headerParts[3].trim());
        int py = Integer.parseInt(headerParts[4].trim());
        int pz = Integer.parseInt(headerParts[5].trim());

        Compressor.Model model = new Compressor.Model();
        model.X = X;
        model.Y = Y;
        model.Z = Z;
        model.px = px;
        model.py = py;
        model.pz = pz;

        // Read tag,label pairs until blank line
        while (true) {
            String line = scanner.readLine();
            if (line == null || line.trim().isEmpty()) {
                break;
            }
            int commaIdx = line.indexOf(',');
            if (commaIdx != -1) {
                String tagStr = line.substring(0, commaIdx).trim();
                String labelStr = line.substring(commaIdx + 1).trim();
                if (!tagStr.isEmpty()) {
                    char tagChar = tagStr.charAt(0);
                    model.tagToLabel[tagChar] = labelStr;
                }
            }
        }

        // Read grid cells
        int totalCells = X * Y * Z;
        model.grid = new byte[totalCells];

        for (int z = 0; z < Z; z++) {
            // Slices in 3D might be separated by blank lines
            for (int y = 0; y < Y; y++) {
                String row = scanner.readLine();
                while (row != null && row.trim().isEmpty()) {
                    row = scanner.readLine();
                }
                if (row == null) break;
                int baseOffset = (z * Y + y) * X;
                for (int x = 0; x < X; x++) {
                    model.grid[baseOffset + x] = (byte) row.charAt(x);
                }
            }
        }

        // Run compressor
        List<Compressor.Region> regions = Compressor.compress(model);

        // Output regions
        boolean is2D = (Z == 1);
        for (Compressor.Region r : regions) {
            String label = model.tagToLabel[r.label & 0xFF];
            if (label == null) {
                label = String.valueOf((char) r.label);
            }
            if (is2D) {
                writer.writeInt(r.x);
                writer.writeChar(',');
                writer.writeInt(r.y);
                writer.writeChar(',');
                writer.writeInt(r.w);
                writer.writeChar(',');
                writer.writeInt(r.h);
                writer.writeChar(',');
                writer.writeString(label);
                writer.writeChar('\n');
            } else {
                writer.writeInt(r.x);
                writer.writeChar(',');
                writer.writeInt(r.y);
                writer.writeChar(',');
                writer.writeInt(r.z);
                writer.writeChar(',');
                writer.writeInt(r.w);
                writer.writeChar(',');
                writer.writeInt(r.h);
                writer.writeChar(',');
                writer.writeInt(r.d);
                writer.writeChar(',');
                writer.writeString(label);
                writer.writeChar('\n');
            }
        }

        writer.flush();
    }

    public static class FastScanner {
        private final InputStream in;
        private final byte[] buffer = new byte[65536];
        private int head = 0;
        private int tail = 0;

        public FastScanner(InputStream in) {
            this.in = in;
        }

        private int readByte() throws IOException {
            if (head >= tail) {
                head = 0;
                tail = in.read(buffer, 0, buffer.length);
                if (tail <= 0) return -1;
            }
            return buffer[head++];
        }

        public String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c = readByte();
            if (c == -1) return null;
            while (c != -1 && c != '\n' && c != '\r') {
                sb.append((char) c);
                c = readByte();
            }
            if (c == '\r') {
                int next = readByte();
                if (next != '\n' && next != -1) {
                    head--;
                }
            }
            return sb.toString();
        }
    }

    public static class FastWriter {
        private final OutputStream out;
        private final byte[] buffer = new byte[65536];
        private int ptr = 0;

        public FastWriter(OutputStream out) {
            this.out = out;
        }

        public void writeChar(char c) throws IOException {
            if (ptr >= buffer.length) flush();
            buffer[ptr++] = (byte) c;
        }

        public void writeString(String s) throws IOException {
            int len = s.length();
            for (int i = 0; i < len; i++) {
                if (ptr >= buffer.length) flush();
                buffer[ptr++] = (byte) s.charAt(i);
            }
        }

        public void writeInt(int v) throws IOException {
            if (v == 0) {
                writeChar('0');
                return;
            }
            if (v < 0) {
                writeChar('-');
                v = -v;
            }
            char[] digits = new char[12];
            int pos = 0;
            while (v > 0) {
                digits[pos++] = (char) ('0' + (v % 10));
                v /= 10;
            }
            for (int i = pos - 1; i >= 0; i--) {
                if (ptr >= buffer.length) flush();
                buffer[ptr++] = (byte) digits[i];
            }
        }

        public void flush() throws IOException {
            if (ptr > 0) {
                out.write(buffer, 0, ptr);
                ptr = 0;
            }
            out.flush();
        }
    }
}

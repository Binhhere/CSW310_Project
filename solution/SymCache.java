import java.util.Arrays;

/**
 * High-performance 8.625 MiB Byte-Ring Symmetry Cache for 2D/3D parent blocks.
 * Designed by GPT-6.2-Astra-Max for CSW310 Spatial Block Compression Challenge.
 *
 * Enforces canonical dimension ordering (W >= H >= D), tests all 48 3D transforms,
 * canonicalizes labels 0..k-1 per orientation, and checks full byte-array equality on lookup.
 * Memory: exactly 8.0 MiB ring + 256 KiB hash + 256 KiB at + 128 KiB len = 8.625 MiB.
 */
public final class SymCache {

    public interface Boxes {
        void box(int x0, int y0, int z0, int w, int h, int d, byte label);
    }

    static final int[][] P = {
        {0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}
    };

    public static final class Canon {
        final int[] n = new int[3], st = new int[3];
        int[] seen, val, key, tmp;
        int W, H, D, perm, flip, epoch;

        public Canon(int cap) {
            key = new int[cap]; tmp = new int[cap]; seen = new int[cap]; val = new int[cap];
        }

        static long add(long h, int v) { return (h ^ (v & 0xffffffffL)) * 0x100000001b3L; }

        public long make(byte[] a, int w, int h, int d) {
            n[0] = w; n[1] = h; n[2] = d; 
            st[0] = 1; st[1] = w; st[2] = w * h;
            W = Math.max(w, Math.max(h, d)); 
            D = Math.min(w, Math.min(h, d)); 
            H = w + h + d - W - D;
            boolean got = false;

            for (int pi = 0; pi < 6; pi++) {
                int[] p = P[pi];
                if (n[p[0]] != W || n[p[1]] != H || n[p[2]] != D) continue;
                ori: for (int f = 0; f < 8; f++) {
                    int sx = st[p[0]], sy = st[p[1]], sz = st[p[2]], base = 0;
                    if ((f & 1) != 0) { if (W == 1) continue; base += (W - 1) * sx; sx = -sx; }
                    if ((f & 2) != 0) { if (H == 1) continue; base += (H - 1) * sy; sy = -sy; }
                    if ((f & 4) != 0) { if (D == 1) continue; base += (D - 1) * sz; sz = -sz; }
                    if (++epoch == 0) { Arrays.fill(seen, 0); epoch = 1; }
                    boolean less = !got;
                    int k = 0, next = 0;
                    for (int z = 0, bz = base; z < D; z++, bz += sz) {
                        for (int y = 0, by = bz; y < H; y++, by += sy) {
                            for (int x = 0, t = by; x < W; x++, t += sx) {
                                int v = a[t] & 0xFF;
                                if (seen[v] != epoch) { seen[v] = epoch; val[v] = next++; }
                                int c = tmp[k] = val[v];
                                if (!less) {
                                    if (c > key[k]) continue ori;
                                    if (c < key[k]) less = true;
                                }
                                ++k;
                            }
                        }
                    }
                    if (less) {
                        got = true; perm = pi; flip = f;
                        int[] swap = key; key = tmp; tmp = swap;
                    }
                }
            }
            long g = add(add(add(0xcbf29ce484222325L, W), H), D);
            int v = w * h * d;
            for (int i = 0; i < v; i++) g = add(g, key[i]);
            g = (g ^ (g >>> 33)) * 0xff51afd7ed558ccdL;
            return g ^ (g >>> 33);
        }
    }

    public static final class Ring {
        static final int BYTES = 1 << 23, SLOTS = 1 << 15; // 8 MiB buffer + 32K slots
        final byte[] mem = new byte[BYTES];
        final long[] hash = new long[SLOTS];
        final long[] at = new long[SLOTS];
        final int[] len = new int[SLOTS];
        long tail;

        int rd(int p) { return (mem[p] & 255) | ((mem[p + 1] & 255) << 8); }
        void wr(int p, int v) { mem[p] = (byte) v; mem[p + 1] = (byte) (v >>> 8); }

        public int find(long k, Canon c) {
            int s = (int) k & (SLOTS - 1);
            if (len[s] == 0 || hash[s] != k || at[s] < tail - BYTES) return -1;
            int p = (int) (at[s] & (BYTES - 1));
            if (rd(p) != c.W || rd(p + 2) != c.H || rd(p + 4) != c.D) return -1;
            int v = c.W * c.H * c.D;
            for (int i = 0; i < v; i++) if (rd(p + 8 + 2 * i) != c.key[i]) return -1;
            return p;
        }

        public void put(long k, Canon c, int nb, int[] lo, int[] hi) {
            int v = c.W * c.H * c.D, bytes = 8 + 2 * v + 4 * nb;
            int p = (int) (tail & (BYTES - 1));
            if (p + bytes > BYTES) { tail += BYTES - p; p = 0; }
            wr(p, c.W); wr(p + 2, c.H); wr(p + 4, c.D); wr(p + 6, nb);
            int t = p + 8;
            for (int i = 0; i < v; i++, t += 2) wr(t, c.key[i]);
            for (int i = 0; i < nb; i++, t += 4) { wr(t, lo[i]); wr(t + 2, hi[i]); }
            int s = (int) k & (SLOTS - 1);
            hash[s] = k; at[s] = tail; len[s] = bytes; tail += bytes;
        }

        public void emit(int rec, Canon c, byte[] orig, int w, int h, int d, Boxes out) {
            int W = c.W, H = c.H;
            int nb = rd(rec + 6), v = W * H * c.D, base = rec + 8 + 2 * v;
            int a0 = P[c.perm][0], a1 = P[c.perm][1], a2 = P[c.perm][2];
            int n0 = (a0 == 0 ? w : (a0 == 1 ? h : d));
            int n1 = (a1 == 0 ? w : (a1 == 1 ? h : d));
            int n2 = (a2 == 0 ? w : (a2 == 1 ? h : d));
            boolean f0 = (c.flip & 1) != 0, f1 = (c.flip & 2) != 0, f2 = (c.flip & 4) != 0;

            for (int i = 0; i < nb; i++) {
                int lo = rd(base + 4 * i), hi = rd(base + 4 * i + 2);
                int t = lo, cx0 = t % W; t /= W; int cy0 = t % H, cz0 = t / H;
                t = hi; int ex = t % W; t /= W; int ey = t % H, ez = t / H;
                int cx1 = ex + 1, cy1 = ey + 1, cz1 = ez + 1;
                int nl0 = f0 ? n0 - cx1 : cx0, nu0 = f0 ? n0 - cx0 : cx1;
                int nl1 = f1 ? n1 - cy1 : cy0, nu1 = f1 ? n1 - cy0 : cy1;
                int nl2 = f2 ? n2 - cz1 : cz0, nu2 = f2 ? n2 - cz0 : cz1;
                int x0 = 0, x1 = 0, y0 = 0, y1 = 0, z0 = 0, z1 = 0;
                if (a0 == 0) { x0 = nl0; x1 = nu0; } else if (a0 == 1) { y0 = nl0; y1 = nu0; } else { z0 = nl0; z1 = nu0; }
                if (a1 == 0) { x0 = nl1; x1 = nu1; } else if (a1 == 1) { y0 = nl1; y1 = nu1; } else { z0 = nl1; z1 = nu1; }
                if (a2 == 0) { x0 = nl2; x1 = nu2; } else if (a2 == 1) { y0 = nl2; y1 = nu2; } else { z0 = nl2; z1 = nu2; }
                byte label = orig[x0 + w * (y0 + h * z0)];
                out.box(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, label);
            }
        }
    }
}

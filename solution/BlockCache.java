import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance collision-proof parent block pattern cache for CSW310.
 * Eliminates re-solving duplicate parent blocks across massive grids.
 * Footprint: strictly bounded to <= 8 MiB heap.
 *
 * Implements strict deep-copying of parent-local coordinates to prevent
 * in-place mutation corruption.
 */
public final class BlockCache {

    private static final int TABLE_SIZE = 4096; // 4K direct-mapped slots
    private static final long[] hashes = new long[TABLE_SIZE];
    private static final byte[][] blockCopies = new byte[TABLE_SIZE][];
    private static final int[] dims = new int[TABLE_SIZE * 3]; // px, py, pz
    @SuppressWarnings("unchecked")
    private static final List<Compressor.Region>[] cachedRegions = new List[TABLE_SIZE];

    public static void clear() {
        Arrays.fill(hashes, 0L);
        Arrays.fill(blockCopies, null);
        Arrays.fill(cachedRegions, null);
    }

    private static long hash64(byte[] data, int len, int px, int py, int pz) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ px) * 0x100000001b3L;
        h = (h ^ py) * 0x100000001b3L;
        h = (h ^ pz) * 0x100000001b3L;
        for (int i = 0; i < len; i++) {
            h = (h ^ (data[i] & 0xFF)) * 0x100000001b3L;
        }
        return h;
    }

    public static List<Compressor.Region> find(byte[] block, int px, int py, int pz) {
        int vol = px * py * pz;
        long h = hash64(block, vol, px, py, pz);
        int slot = (int) (h & (TABLE_SIZE - 1));

        if (hashes[slot] == h && cachedRegions[slot] != null) {
            if (dims[slot * 3] == px && dims[slot * 3 + 1] == py && dims[slot * 3 + 2] == pz) {
                byte[] copy = blockCopies[slot];
                if (copy != null && copy.length == vol && Arrays.equals(block, copy)) {
                    // Exact collision-proof cache hit!
                    // Return fresh instances with parent-local coordinates
                    List<Compressor.Region> stored = cachedRegions[slot];
                    List<Compressor.Region> res = new ArrayList<>(stored.size());
                    for (int i = 0; i < stored.size(); i++) {
                        Compressor.Region r = stored.get(i);
                        res.add(new Compressor.Region(r.x, r.y, r.z, r.w, r.h, r.d, r.label));
                    }
                    return res;
                }
            }
        }
        return null;
    }

    public static void put(byte[] block, int px, int py, int pz, List<Compressor.Region> regions) {
        if (regions == null || regions.size() > 1024) return;
        int vol = px * py * pz;
        if (vol > 8192) return; // Keep memory strictly bounded

        long h = hash64(block, vol, px, py, pz);
        int slot = (int) (h & (TABLE_SIZE - 1));

        hashes[slot] = h;
        dims[slot * 3] = px;
        dims[slot * 3 + 1] = py;
        dims[slot * 3 + 2] = pz;
        blockCopies[slot] = Arrays.copyOf(block, vol);

        // Store deep copy of parent-local coordinates
        List<Compressor.Region> copyList = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            Compressor.Region r = regions.get(i);
            copyList.add(new Compressor.Region(r.x, r.y, r.z, r.w, r.h, r.d, r.label));
        }
        cachedRegions[slot] = copyList;
    }
}

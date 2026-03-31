package grabflow.location.geo;

/**
 * Simplified H3-style hexagonal geospatial index.
 *
 * <h3>CS Fundamental: Geospatial Indexing</h3>
 * <p>H3 (developed by Uber) partitions the Earth's surface into hexagonal cells at
 * multiple resolutions. Hexagons are preferred over squares because they have uniform
 * adjacency (6 neighbors, all equidistant from center) and minimize edge effects
 * compared to rectangular grids.</p>
 *
 * <h3>How It Works</h3>
 * <p>This implementation uses a simplified planar projection approach:</p>
 * <ol>
 *   <li>Convert lat/lng to a flat (x, y) using Mercator-like projection</li>
 *   <li>Map (x, y) to axial hex coordinates (q, r) using hex grid math</li>
 *   <li>Encode (resolution, q, r) into a 64-bit cell ID</li>
 * </ol>
 *
 * <h3>Hex Grid Coordinate System (Axial/Cube)</h3>
 * <pre>
 *        _____
 *       /     \
 *  ____/ (0,-1)\____
 * /    \       /    \
 * |(-1,0)     |(1,-1)|
 * \____/ (0,0) \____/
 * /    \       /    \
 * |(-1,1)     |(1, 0)|
 * \____/ (0,1) \____/
 *      \       /
 *       \_____/
 * </pre>
 * <p>Axial coordinates (q, r) identify each hexagon. The third cube coordinate
 * s = -q - r is implicit. Neighbors are found by adding direction vectors.</p>
 *
 * <h3>Resolution Levels</h3>
 * <table>
 *   <tr><th>Res</th><th>Edge (m)</th><th>Area (km²)</th><th>Use Case</th></tr>
 *   <tr><td>5</td><td>~8,500</td><td>~252</td><td>Regional</td></tr>
 *   <tr><td>7</td><td>~1,220</td><td>~5.16</td><td>City district</td></tr>
 *   <tr><td>9</td><td>~174</td><td>~0.105</td><td>Street-level matching</td></tr>
 *   <tr><td>11</td><td>~25</td><td>~0.002</td><td>Building-level</td></tr>
 * </table>
 */
public class H3Index {

    /** Maximum supported resolution (0-15) */
    public static final int MAX_RESOLUTION = 15;

    /** Six axial direction vectors for hex neighbor traversal */
    private static final int[][] AXIAL_DIRECTIONS = {
            { 1,  0}, { 1, -1}, { 0, -1},
            {-1,  0}, {-1,  1}, { 0,  1}
    };

    /**
     * Approximate edge length in meters for each resolution.
     * Derived from H3's real values: res 0 = 1107.7 km, each level divides by ~sqrt(7).
     */
    private static final double[] EDGE_LENGTHS_METERS = new double[MAX_RESOLUTION + 1];
    static {
        EDGE_LENGTHS_METERS[0] = 1_107_712.591;
        for (int r = 1; r <= MAX_RESOLUTION; r++) {
            EDGE_LENGTHS_METERS[r] = EDGE_LENGTHS_METERS[r - 1] / Math.sqrt(7.0);
        }
    }

    private H3Index() {} // static utility class

    // ── Cell ID Encoding ──

    /**
     * Converts a GPS coordinate to an H3-style hexagonal cell ID.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Project lat/lng to planar (x, y) in meters from origin (0, 0)</li>
     *   <li>Compute hex size for the given resolution</li>
     *   <li>Convert (x, y) to fractional axial coordinates (q_f, r_f)</li>
     *   <li>Round to nearest hex center using cube-coordinate rounding</li>
     *   <li>Encode (resolution, q, r) into a 64-bit long</li>
     * </ol>
     *
     * @param lat        latitude in degrees [-90, 90]
     * @param lng        longitude in degrees [-180, 180]
     * @param resolution hex resolution [0, 15]
     * @return 64-bit cell ID
     */
    public static long latLngToCell(double lat, double lng, int resolution) {
        validateResolution(resolution);

        // Step 1: Project to planar coordinates (meters)
        double x = lngToX(lng);
        double y = latToY(lat);

        // Step 2: Hex size (distance from center to edge) for this resolution
        double hexSize = EDGE_LENGTHS_METERS[resolution];

        // Step 3: Convert (x, y) to fractional axial hex coordinates
        // For pointy-top hexagons:
        //   q = (sqrt(3)/3 * x  -  1/3 * y) / hexSize
        //   r = (2/3 * y) / hexSize
        double q_f = (Math.sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * y) / hexSize;
        double r_f = (2.0 / 3.0 * y) / hexSize;

        // Step 4: Round fractional hex to nearest integer hex (cube rounding)
        int[] qr = hexRound(q_f, r_f);

        // Step 5: Encode
        return encodeCellId(resolution, qr[0], qr[1]);
    }

    /**
     * Returns the center lat/lng of a cell.
     *
     * @param cellId the H3 cell ID
     * @return array of [lat, lng] in degrees
     */
    public static double[] cellToLatLng(long cellId) {
        int resolution = getResolution(cellId);
        int[] qr = decodeAxial(cellId);
        int q = qr[0];
        int r = qr[1];
        double hexSize = EDGE_LENGTHS_METERS[resolution];

        // Inverse of the axial-to-pixel conversion (pointy-top):
        //   x = hexSize * sqrt(3) * (q + r/2)
        //   y = hexSize * 3/2 * r
        double x = hexSize * Math.sqrt(3.0) * (q + r / 2.0);
        double y = hexSize * 1.5 * r;

        double lat = yToLat(y);
        double lng = xToLng(x);
        return new double[]{lat, lng};
    }

    /**
     * Returns all cells within k rings of the center cell (inclusive).
     *
     * <p>Ring 0 = center cell only. Ring 1 = 6 immediate neighbors.
     * Total cells for k rings = 3k² + 3k + 1.</p>
     *
     * <p>Algorithm: start at center, walk outward in concentric hex rings.
     * For ring i, start at direction 4 offset by i, then walk 6 sides of i steps each.</p>
     *
     * @param cellId center cell
     * @param k      ring distance (0 = just center)
     * @return array of cell IDs (center first, then rings outward)
     */
    public static long[] kRing(long cellId, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        if (k == 0) return new long[]{cellId};

        int resolution = getResolution(cellId);
        int[] center = decodeAxial(cellId);
        int totalCells = 3 * k * k + 3 * k + 1;
        long[] result = new long[totalCells];
        int idx = 0;

        // Center cell
        result[idx++] = cellId;

        // Walk concentric rings
        int q = center[0];
        int r = center[1];

        for (int ring = 1; ring <= k; ring++) {
            // Start position: move ring steps in direction 4 (which is (-1, 1))
            q += AXIAL_DIRECTIONS[4][0];
            r += AXIAL_DIRECTIONS[4][1];

            // Walk 6 sides, each with 'ring' steps
            for (int side = 0; side < 6; side++) {
                for (int step = 0; step < ring; step++) {
                    result[idx++] = encodeCellId(resolution, q, r);
                    q += AXIAL_DIRECTIONS[side][0];
                    r += AXIAL_DIRECTIONS[side][1];
                }
            }
        }

        return result;
    }

    /**
     * Extracts the resolution from a cell ID.
     */
    public static int getResolution(long cellId) {
        return (int) ((cellId >>> 52) & 0xF);
    }

    /**
     * Returns the parent cell at a coarser resolution.
     *
     * @param cellId           fine-resolution cell
     * @param parentResolution coarser resolution (must be < cell's resolution)
     * @return parent cell ID
     */
    public static long parentCell(long cellId, int parentResolution) {
        int childRes = getResolution(cellId);
        if (parentResolution >= childRes) {
            throw new IllegalArgumentException(
                    "Parent resolution " + parentResolution + " must be < child resolution " + childRes);
        }
        double[] latLng = cellToLatLng(cellId);
        return latLngToCell(latLng[0], latLng[1], parentResolution);
    }

    /**
     * Returns the approximate edge length in meters for a given resolution.
     */
    public static double cellEdgeLengthMeters(int resolution) {
        validateResolution(resolution);
        return EDGE_LENGTHS_METERS[resolution];
    }

    // ── Cell ID Bit Layout ──
    // Bits 63-56: mode + reserved (0x01 = cell mode)
    // Bits 55-52: resolution (0-15)
    // Bits 51-26: q coordinate (signed, 26 bits -> range ±33 million)
    // Bits 25-0:  r coordinate (signed, 26 bits -> range ±33 million)

    static long encodeCellId(int resolution, int q, int r) {
        long mode = 0x01L;
        long res = resolution & 0xFL;
        // Store q and r as unsigned 26-bit values (add offset to handle negatives)
        long qBits = (q + (1 << 25)) & 0x3FFFFFFL;
        long rBits = (r + (1 << 25)) & 0x3FFFFFFL;
        return (mode << 56) | (res << 52) | (qBits << 26) | rBits;
    }

    static int[] decodeAxial(long cellId) {
        int qRaw = (int) ((cellId >>> 26) & 0x3FFFFFF);
        int rRaw = (int) (cellId & 0x3FFFFFF);
        int q = qRaw - (1 << 25);
        int r = rRaw - (1 << 25);
        return new int[]{q, r};
    }

    // ── Hex Rounding ──

    /**
     * Rounds fractional axial coordinates to the nearest hex center
     * using cube coordinate rounding.
     *
     * <p>Algorithm (from Red Blob Games):</p>
     * <ol>
     *   <li>Convert axial (q, r) to cube (q, r, s) where s = -q - r</li>
     *   <li>Round each to nearest integer</li>
     *   <li>The rounding may violate q + r + s = 0</li>
     *   <li>Find the component with the largest rounding error</li>
     *   <li>Reset that component to -sum of the other two</li>
     * </ol>
     */
    static int[] hexRound(double q_f, double r_f) {
        double s_f = -q_f - r_f;

        int q = (int) Math.round(q_f);
        int r = (int) Math.round(r_f);
        int s = (int) Math.round(s_f);

        double qDiff = Math.abs(q - q_f);
        double rDiff = Math.abs(r - r_f);
        double sDiff = Math.abs(s - s_f);

        if (qDiff > rDiff && qDiff > sDiff) {
            q = -r - s;
        } else if (rDiff > sDiff) {
            r = -q - s;
        }
        // else s is reset implicitly (we only need q, r for axial)

        return new int[]{q, r};
    }

    // ── Coordinate Projection ──

    /**
     * Projects longitude to x-coordinate in meters using Mercator.
     * x = R * lng_rad * cos(reference_lat)
     * Using reference lat = 0 (equator) for simplicity.
     */
    static double lngToX(double lng) {
        return GeoUtils.EARTH_RADIUS_METERS * Math.toRadians(lng);
    }

    /**
     * Projects latitude to y-coordinate in meters using Mercator.
     * y = R * lat_rad (simple cylindrical for moderate latitudes)
     */
    static double latToY(double lat) {
        return GeoUtils.EARTH_RADIUS_METERS * Math.toRadians(lat);
    }

    static double xToLng(double x) {
        return Math.toDegrees(x / GeoUtils.EARTH_RADIUS_METERS);
    }

    static double yToLat(double y) {
        return Math.toDegrees(y / GeoUtils.EARTH_RADIUS_METERS);
    }

    private static void validateResolution(int resolution) {
        if (resolution < 0 || resolution > MAX_RESOLUTION) {
            throw new IllegalArgumentException(
                    "Resolution must be in [0, " + MAX_RESOLUTION + "], got: " + resolution);
        }
    }
}

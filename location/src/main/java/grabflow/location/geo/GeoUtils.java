package grabflow.location.geo;

/**
 * Pure static utility class for spherical geometry calculations on the WGS-84 ellipsoid
 * approximated as a sphere of mean radius {@value #EARTH_RADIUS_METERS} metres.
 *
 * <p>All methods accept and return coordinates in decimal degrees unless stated otherwise.
 * Internally, angles are converted to radians for trigonometric operations.
 *
 * <p>This class cannot be instantiated.
 */
public final class GeoUtils {

    /** Mean radius of the Earth in metres, used for all distance calculations. */
    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    // Prevent instantiation
    private GeoUtils() {
        throw new UnsupportedOperationException("GeoUtils is a static utility class");
    }

    // -------------------------------------------------------------------------
    // Angle conversion
    // -------------------------------------------------------------------------

    /**
     * Converts an angle in degrees to radians.
     *
     * <p>Wrapper around {@link Math#toRadians(double)} provided for readability at call sites.
     *
     * @param degrees angle in degrees
     * @return angle in radians
     */
    public static double toRadians(double degrees) {
        return Math.toRadians(degrees);
    }

    /**
     * Converts an angle in radians to degrees.
     *
     * <p>Wrapper around {@link Math#toDegrees(double)} provided for readability at call sites.
     *
     * @param radians angle in radians
     * @return angle in degrees
     */
    public static double toDegrees(double radians) {
        return Math.toDegrees(radians);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given latitude/longitude pair forms a valid GPS coordinate.
     *
     * <p>A coordinate is valid when:
     * <ul>
     *   <li>{@code lat} is in the range {@code [-90, 90]}</li>
     *   <li>{@code lng} is in the range {@code [-180, 180]}</li>
     *   <li>Neither value is {@link Double#NaN} or infinite</li>
     * </ul>
     *
     * @param lat latitude in decimal degrees
     * @param lng longitude in decimal degrees
     * @return {@code true} if the coordinate is valid; {@code false} otherwise
     */
    public static boolean isValidCoordinate(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return false;
        if (Double.isInfinite(lat) || Double.isInfinite(lng)) return false;
        return lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0;
    }

    // -------------------------------------------------------------------------
    // Distance
    // -------------------------------------------------------------------------

    /**
     * Computes the great-circle distance in metres between two GPS coordinates using
     * the Haversine formula.
     *
     * <p>Formula:
     * <pre>
     *   a = sin²(Δlat/2) + cos(lat1) · cos(lat2) · sin²(Δlng/2)
     *   d = 2R · atan2(√a, √(1−a))
     * </pre>
     *
     * <p>The Haversine formula is numerically well-conditioned for small distances,
     * unlike the spherical law of cosines. Maximum error is on the order of 0.3%
     * compared to geodesic distance on the WGS-84 ellipsoid.
     *
     * @param lat1 latitude of point 1 in decimal degrees
     * @param lng1 longitude of point 1 in decimal degrees
     * @param lat2 latitude of point 2 in decimal degrees
     * @param lng2 longitude of point 2 in decimal degrees
     * @return great-circle distance in metres
     */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);

        double sinDPhi = Math.sin(dPhi / 2);
        double sinDLambda = Math.sin(dLambda / 2);

        double a = sinDPhi * sinDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinDLambda * sinDLambda;

        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_METERS * c;
    }

    // -------------------------------------------------------------------------
    // Bearing
    // -------------------------------------------------------------------------

    /**
     * Computes the initial bearing (forward azimuth) in degrees from point 1 to point 2.
     *
     * <p>Formula:
     * <pre>
     *   θ = atan2(sin(Δlng) · cos(lat2),
     *             cos(lat1) · sin(lat2) − sin(lat1) · cos(lat2) · cos(Δlng))
     * </pre>
     *
     * <p>The result is normalised to the range {@code [0, 360)}: due North is {@code 0°},
     * due East is {@code 90°}, due South is {@code 180°}, due West is {@code 270°}.
     *
     * @param lat1 latitude of origin in decimal degrees
     * @param lng1 longitude of origin in decimal degrees
     * @param lat2 latitude of destination in decimal degrees
     * @param lng2 longitude of destination in decimal degrees
     * @return initial bearing in degrees, in the range {@code [0, 360)}
     */
    public static double bearingDegrees(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lng2 - lng1);

        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);

        double bearingRad = Math.atan2(y, x);
        // Normalise to [0, 360)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0;
    }

    // -------------------------------------------------------------------------
    // Destination point
    // -------------------------------------------------------------------------

    /**
     * Projects a destination point given an origin, initial bearing, and distance.
     *
     * <p>Formula (spherical Earth):
     * <pre>
     *   φ₂ = asin(sin(φ₁) · cos(d/R) + cos(φ₁) · sin(d/R) · cos(θ))
     *   λ₂ = λ₁ + atan2(sin(θ) · sin(d/R) · cos(φ₁),
     *                    cos(d/R) − sin(φ₁) · sin(φ₂))
     * </pre>
     * where {@code φ} is latitude, {@code λ} is longitude, {@code d} is distance,
     * {@code R} is the Earth radius, and {@code θ} is the bearing — all angles in radians.
     *
     * @param lat           latitude of origin in decimal degrees
     * @param lng           longitude of origin in decimal degrees
     * @param bearingDeg    initial bearing in decimal degrees (0 = North, 90 = East)
     * @param distanceMeters distance to travel in metres
     * @return {@code double[2]} where {@code [0]} is the destination latitude and
     *         {@code [1]} is the destination longitude, both in decimal degrees
     */
    public static double[] destinationPoint(double lat, double lng,
                                             double bearingDeg, double distanceMeters) {
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lng);
        double theta = Math.toRadians(bearingDeg);
        double delta = distanceMeters / EARTH_RADIUS_METERS;  // angular distance in radians

        double sinPhi1 = Math.sin(phi1);
        double cosPhi1 = Math.cos(phi1);
        double sinDelta = Math.sin(delta);
        double cosDelta = Math.cos(delta);
        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);

        double sinPhi2 = sinPhi1 * cosDelta + cosPhi1 * sinDelta * cosTheta;
        double phi2 = Math.asin(sinPhi2);

        double y = sinTheta * sinDelta * cosPhi1;
        double x = cosDelta - sinPhi1 * sinPhi2;
        double lambda2 = lambda1 + Math.atan2(y, x);

        // Normalise longitude to [-180, 180]
        double destLng = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0;
        double destLat = Math.toDegrees(phi2);

        return new double[]{destLat, destLng};
    }
}

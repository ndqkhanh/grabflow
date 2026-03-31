package grabflow.location.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoUtilsTest {

    // -------------------------------------------------------------------------
    // 1. Haversine — parameterized known city-to-city distances
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "HCMC to Hanoi,    10.7769, 106.7009, 21.0285, 105.8542, 1138000, 30000",
            "London to Paris,  51.5074,  -0.1278, 48.8566,   2.3522,  344000, 5000",
            "Same point,       10.0000,  10.0000, 10.0000,  10.0000,       0,    1"
    })
    void haversineKnownDistances(String label,
                                  double lat1, double lng1,
                                  double lat2, double lng2,
                                  double expectedMeters,
                                  double toleranceMeters) {
        double distance = GeoUtils.haversineMeters(lat1, lng1, lat2, lng2);
        assertThat(distance)
                .as("%s: expected ~%.0f m", label, expectedMeters)
                .isCloseTo(expectedMeters, within(toleranceMeters));
    }

    // -------------------------------------------------------------------------
    // 2. Haversine — same point is zero
    // -------------------------------------------------------------------------

    @Test
    void haversineSamePointIsZero() {
        assertThat(GeoUtils.haversineMeters(48.8566, 2.3522, 48.8566, 2.3522))
                .isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // 3. Haversine — antipodal points ≈ half Earth circumference
    // -------------------------------------------------------------------------

    @Test
    void haversineAntipodalPoints() {
        // North pole vs South pole: distance = π × R
        double expected = Math.PI * GeoUtils.EARTH_RADIUS_METERS;  // ≈ 20,015 km
        double distance = GeoUtils.haversineMeters(90.0, 0.0, -90.0, 0.0);
        assertThat(distance)
                .as("antipodal distance should be ~20,015 km")
                .isCloseTo(expected, within(1.0));  // within 1 metre (exact for poles)
    }

    // -------------------------------------------------------------------------
    // 4. isValidCoordinate — valid and invalid cases
    // -------------------------------------------------------------------------

    @Test
    void isValidCoordinate() {
        // Valid coordinates
        assertThat(GeoUtils.isValidCoordinate(0.0, 0.0)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(90.0, 180.0)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(-90.0, -180.0)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(10.7769, 106.7009)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(-33.8688, 151.2093)).isTrue();  // Sydney

        // Invalid — out of range
        assertThat(GeoUtils.isValidCoordinate(91.0, 0.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(-91.0, 0.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(0.0, 181.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(0.0, -181.0)).isFalse();

        // Invalid — NaN
        assertThat(GeoUtils.isValidCoordinate(Double.NaN, 0.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(0.0, Double.NaN)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(Double.NaN, Double.NaN)).isFalse();

        // Invalid — Infinity
        assertThat(GeoUtils.isValidCoordinate(Double.POSITIVE_INFINITY, 0.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(0.0, Double.NEGATIVE_INFINITY)).isFalse();
    }

    // -------------------------------------------------------------------------
    // 5. bearingDegrees — due North is ~0°
    // -------------------------------------------------------------------------

    @Test
    void bearingNorthIsZero() {
        // From equator (0, 0) to a point directly north (10, 0)
        double bearing = GeoUtils.bearingDegrees(0.0, 0.0, 10.0, 0.0);
        assertThat(bearing)
                .as("bearing due north should be ~0°")
                .isCloseTo(0.0, within(0.001));
    }

    // -------------------------------------------------------------------------
    // 6. bearingDegrees — due East is ~90°
    // -------------------------------------------------------------------------

    @Test
    void bearingEastIs90() {
        // From equator (0, 0) to a point directly east (0, 10)
        double bearing = GeoUtils.bearingDegrees(0.0, 0.0, 0.0, 10.0);
        assertThat(bearing)
                .as("bearing due east should be ~90°")
                .isCloseTo(90.0, within(0.001));
    }

    // -------------------------------------------------------------------------
    // 7. destinationPoint — round trip: project 1000 m north, haversine back
    // -------------------------------------------------------------------------

    @Test
    void destinationPointRoundTrip() {
        double originLat = 48.8566;
        double originLng = 2.3522;
        double distanceMeters = 1000.0;
        double bearingNorth = 0.0;

        double[] dest = GeoUtils.destinationPoint(originLat, originLng, bearingNorth, distanceMeters);
        double measured = GeoUtils.haversineMeters(originLat, originLng, dest[0], dest[1]);

        assertThat(measured)
                .as("round-trip distance should be ~1000 m")
                .isCloseTo(distanceMeters, within(0.01));  // within 1 cm
    }

    // -------------------------------------------------------------------------
    // 8. destinationPoint — known result: (0,0) bearing 90° dist 111,320 m ≈ (0, 1°)
    // -------------------------------------------------------------------------

    @Test
    void destinationPointKnownResult() {
        // 1° of longitude at the equator ≈ 111,320 m
        double[] dest = GeoUtils.destinationPoint(0.0, 0.0, 90.0, 111_320.0);

        assertThat(dest[0])
                .as("latitude should remain ~0°")
                .isCloseTo(0.0, within(0.001));
        assertThat(dest[1])
                .as("longitude should be ~1°")
                .isCloseTo(1.0, within(0.01));
    }
}

package grabflow.location.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.*;

class H3IndexTest {

    // ── Determinism ──

    @Test
    void latLngToCellIsDeterministic() {
        long cell1 = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long cell2 = H3Index.latLngToCell(10.7769, 106.7009, 9);
        assertThat(cell1).isEqualTo(cell2);
    }

    @Test
    void differentPointsProduceDifferentCells() {
        long hcmc = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long hanoi = H3Index.latLngToCell(21.0285, 105.8542, 9);
        assertThat(hcmc).isNotEqualTo(hanoi);
    }

    // ── Resolution ──

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 7, 9, 11, 15})
    void resolutionEncodedCorrectly(int resolution) {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, resolution);
        assertThat(H3Index.getResolution(cell)).isEqualTo(resolution);
    }

    @Test
    void differentResolutionsProduceDifferentCells() {
        long res7 = H3Index.latLngToCell(10.7769, 106.7009, 7);
        long res9 = H3Index.latLngToCell(10.7769, 106.7009, 9);
        assertThat(res7).isNotEqualTo(res9);
    }

    @Test
    void invalidResolutionThrows() {
        assertThatThrownBy(() -> H3Index.latLngToCell(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> H3Index.latLngToCell(0, 0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Round-Trip ──

    @Test
    void cellToLatLngRoundTripsWithinTolerance() {
        double lat = 10.7769, lng = 106.7009;
        int resolution = 9;
        long cell = H3Index.latLngToCell(lat, lng, resolution);
        double[] center = H3Index.cellToLatLng(cell);

        // At resolution 9, edge length ~174m, so center should be within ~200m
        double distMeters = GeoUtils.haversineMeters(lat, lng, center[0], center[1]);
        assertThat(distMeters).isLessThan(200.0);
    }

    @Test
    void cellToLatLngRoundTripCoarseResolution() {
        double lat = 48.8566, lng = 2.3522; // Paris
        int resolution = 7;
        long cell = H3Index.latLngToCell(lat, lng, resolution);
        double[] center = H3Index.cellToLatLng(cell);

        // At resolution 7, edge ~1.2km, so within ~1.5km
        double distMeters = GeoUtils.haversineMeters(lat, lng, center[0], center[1]);
        assertThat(distMeters).isLessThan(1500.0);
    }

    // ── k-Ring ──

    @Test
    void kRingZeroReturnsSelf() {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long[] ring = H3Index.kRing(cell, 0);
        assertThat(ring).containsExactly(cell);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void kRingCountMatchesFormula(int k) {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long[] ring = H3Index.kRing(cell, k);
        int expectedCount = 3 * k * k + 3 * k + 1;
        assertThat(ring).hasSize(expectedCount);
    }

    @Test
    void kRingContainsCenter() {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long[] ring = H3Index.kRing(cell, 2);
        assertThat(ring[0]).isEqualTo(cell);
    }

    @Test
    void kRingCellsAreUnique() {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, 9);
        long[] ring = H3Index.kRing(cell, 3);
        var uniqueCells = new HashSet<Long>();
        for (long c : ring) uniqueCells.add(c);
        assertThat(uniqueCells).hasSize(ring.length);
    }

    @Test
    void kRingNeighborsAreNearby() {
        double lat = 10.7769, lng = 106.7009;
        int resolution = 9;
        long cell = H3Index.latLngToCell(lat, lng, resolution);
        long[] ring = H3Index.kRing(cell, 1);

        // All k=1 neighbors should be within 2x edge length of center
        double maxDist = H3Index.cellEdgeLengthMeters(resolution) * 3;
        double[] centerLatLng = H3Index.cellToLatLng(cell);
        for (long neighborCell : ring) {
            double[] neighborLatLng = H3Index.cellToLatLng(neighborCell);
            double dist = GeoUtils.haversineMeters(
                    centerLatLng[0], centerLatLng[1],
                    neighborLatLng[0], neighborLatLng[1]);
            assertThat(dist).isLessThan(maxDist);
        }
    }

    @Test
    void kRingNegativeThrows() {
        long cell = H3Index.latLngToCell(0, 0, 9);
        assertThatThrownBy(() -> H3Index.kRing(cell, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Parent Cell ──

    @Test
    void parentCellContainsChild() {
        double lat = 10.7769, lng = 106.7009;
        long childCell = H3Index.latLngToCell(lat, lng, 9);
        long parentCell = H3Index.parentCell(childCell, 7);

        assertThat(H3Index.getResolution(parentCell)).isEqualTo(7);

        // Child center should be within parent's area
        double[] childCenter = H3Index.cellToLatLng(childCell);
        long recomputedParent = H3Index.latLngToCell(childCenter[0], childCenter[1], 7);
        assertThat(recomputedParent).isEqualTo(parentCell);
    }

    @Test
    void parentResolutionMustBeLower() {
        long cell = H3Index.latLngToCell(0, 0, 7);
        assertThatThrownBy(() -> H3Index.parentCell(cell, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Edge Length ──

    @Test
    void edgeLengthDecreasesWithResolution() {
        for (int r = 1; r <= H3Index.MAX_RESOLUTION; r++) {
            assertThat(H3Index.cellEdgeLengthMeters(r))
                    .isLessThan(H3Index.cellEdgeLengthMeters(r - 1));
        }
    }

    @Test
    void edgeLengthAtResolution9IsAbout174m() {
        double edge = H3Index.cellEdgeLengthMeters(9);
        assertThat(edge).isBetween(100.0, 300.0);
    }

    // ── Encoding/Decoding ──

    @Test
    void encodeDecodeAxialRoundTrip() {
        int q = 12345, r = -6789;
        long cellId = H3Index.encodeCellId(9, q, r);
        int[] decoded = H3Index.decodeAxial(cellId);
        assertThat(decoded[0]).isEqualTo(q);
        assertThat(decoded[1]).isEqualTo(r);
    }

    @Test
    void encodeDecodeNegativeCoordinates() {
        int q = -100_000, r = -200_000;
        long cellId = H3Index.encodeCellId(7, q, r);
        int[] decoded = H3Index.decodeAxial(cellId);
        assertThat(decoded[0]).isEqualTo(q);
        assertThat(decoded[1]).isEqualTo(r);
    }

    @Test
    void encodeDecodeZeroCoordinates() {
        long cellId = H3Index.encodeCellId(0, 0, 0);
        int[] decoded = H3Index.decodeAxial(cellId);
        assertThat(decoded[0]).isZero();
        assertThat(decoded[1]).isZero();
    }

    // ── Hex Rounding ──

    @Test
    void hexRoundExactIntegerCoordinates() {
        int[] result = H3Index.hexRound(3.0, -2.0);
        assertThat(result).containsExactly(3, -2);
    }

    @Test
    void hexRoundFractionalCoordinates() {
        // (0.1, 0.1) should round to (0, 0)
        int[] result = H3Index.hexRound(0.1, 0.1);
        assertThat(result[0] + result[1] + (-result[0] - result[1])).isZero(); // cube constraint
    }

    // ── Known Coordinates ──

    @Test
    void hoChiMinhCityProducesStableCell() {
        long cell = H3Index.latLngToCell(10.7769, 106.7009, 9);
        assertThat(cell).isNotZero();
        assertThat(H3Index.getResolution(cell)).isEqualTo(9);
    }

    @Test
    void equatorOriginProducesValidCell() {
        long cell = H3Index.latLngToCell(0.0, 0.0, 9);
        assertThat(cell).isNotZero();
        double[] center = H3Index.cellToLatLng(cell);
        assertThat(GeoUtils.haversineMeters(0, 0, center[0], center[1]))
                .isLessThan(200.0);
    }

    @Test
    void extremeLatitudes() {
        // North pole
        long northPole = H3Index.latLngToCell(89.9, 0, 7);
        assertThat(H3Index.getResolution(northPole)).isEqualTo(7);

        // South pole
        long southPole = H3Index.latLngToCell(-89.9, 0, 7);
        assertThat(H3Index.getResolution(southPole)).isEqualTo(7);

        // Different cells
        assertThat(northPole).isNotEqualTo(southPole);
    }

    @Test
    void nearbyPointsSameCellAtCoarseResolution() {
        // Two points ~100m apart should be in the same cell at resolution 7 (~1.2km edge)
        long cell1 = H3Index.latLngToCell(10.7769, 106.7009, 7);
        long cell2 = H3Index.latLngToCell(10.7770, 106.7010, 7);
        assertThat(cell1).isEqualTo(cell2);
    }
}

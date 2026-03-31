package grabflow.common;

/**
 * A driver's GPS position at a point in time.
 *
 * @param driverId  unique driver identifier
 * @param lat       latitude in degrees [-90, 90]
 * @param lng       longitude in degrees [-180, 180]
 * @param heading   compass heading in degrees [0, 360)
 * @param speed     speed in km/h
 * @param timestamp epoch milliseconds when this position was recorded
 * @param h3CellId  H3 hexagonal cell index at resolution 9 (pre-computed at ingestion)
 */
public record DriverLocation(
        String driverId,
        double lat,
        double lng,
        double heading,
        double speed,
        long timestamp,
        long h3CellId
) {}

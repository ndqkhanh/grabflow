package grabflow.common;

/**
 * Request to find nearby drivers around a given location.
 *
 * @param lat          latitude of the query point
 * @param lng          longitude of the query point
 * @param radiusMeters search radius in meters
 * @param maxResults   maximum number of drivers to return
 */
public record NearbyDriversRequest(
        double lat,
        double lng,
        double radiusMeters,
        int maxResults
) {}

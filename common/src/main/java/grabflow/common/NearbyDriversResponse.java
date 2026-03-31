package grabflow.common;

import java.util.List;

/**
 * Response containing nearby drivers sorted by distance.
 *
 * @param drivers     list of nearby drivers, closest first
 * @param queryTimeMs time taken to execute the query in milliseconds
 */
public record NearbyDriversResponse(
        List<DriverLocation> drivers,
        long queryTimeMs
) {}

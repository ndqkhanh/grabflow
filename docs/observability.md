# GrabFlow — Observability: Metrics, Monitoring, and Operational Visibility

> **Cross-cutting guide** to monitoring the GrabFlow platform.
> Stack: SLF4J + Logback logging, Prometheus-compatible metrics, Grafana dashboards.
> Philosophy: Every service exposes health, throughput, latency, and error rate.

---

## Table of Contents

1. [Observability Philosophy](#1-observability-philosophy)
2. [Metrics Catalog](#2-metrics-catalog)
3. [Service Health Model](#3-service-health-model)
4. [Logging Strategy](#4-logging-strategy)
5. [Monitoring Dashboard Design](#5-monitoring-dashboard-design)
6. [Alerting Rules](#6-alerting-rules)
7. [See Also](#7-see-also)

---

## 1. Observability Philosophy

Observability in GrabFlow is built on **the three pillars of production visibility**: metrics, logs, and traces. All three work in concert to answer the operational questions that arise when systems fail or degrade.

### The Three Pillars

**Metrics** — Time-series measurements of system behavior aggregated at high cardinality. Metrics are queryable by dimension (service, endpoint, code, zone) and aggregatable (sum, average, percentile, rate). Examples: requests/sec, P99 latency, error rate, cache hit rate. Metrics are lightweight (bytes per series) and cheap to store and query.

**Logs** — Structured text records of events with context. Logs are high-cardinality (every request generates a log entry), high-volume, and searchable. Examples: "Ride 12345 matched at 2026-03-31T10:23:45Z", "Surge updated for H3 cell 0x87283472d9fffff with multiplier 1.5x". Logs are expensive to store but invaluable for root-cause analysis.

**Traces** — Distributed span chains showing the path of a request through the system. A trace captures the call sequence (gateway → pricing → ride → payment) with latencies and errors at each step. Traces are very high cardinality (one per request in production) and used sparingly during investigation.

### Why Observability Matters at 7 Services

GrabFlow is a distributed system with 7 services running independently:
- **Gateway** (request ingress, rate limiting, TLS termination)
- **Location** (driver GPS tracking, nearest-driver queries)
- **Ride** (ride lifecycle state machine, matching logic)
- **Pricing** (surge detection, fare estimation, promo validation)
- **Payment** (charge orchestration, idempotency, saga coordination)
- **Notification** (SMS/push delivery, templating)
- **Demo** (traffic generation for testing)

When a passenger's ride request fails, the root cause could be in any of seven places. Observability lets ops teams narrow down the failure:
1. Metrics identify *which service* is slow or erroring (e.g., "Ride service P99 latency spiked").
2. Logs explain *what happened in that service* (e.g., "DSL compilation timeout on rule engine").
3. Traces show *the full path* from gateway to failure point.

Without observability, debugging production takes hours. With observability, it takes minutes.

---

## 2. Metrics Catalog

Every service in GrabFlow exposes a standard set of metrics in Prometheus format (text exposition, scrapeable on `GET /metrics`). The table below describes the key metrics per service.

### Service: Gateway

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `gateway_http_requests_total` | Counter | Total HTTP requests received | `method`, `endpoint`, `status` |
| `gateway_http_request_duration_seconds` | Histogram | Request latency (seconds) | `method`, `endpoint` |
| `gateway_http_request_duration_seconds_quantile` | Histogram quantile | P50, P95, P99 latencies | `method`, `endpoint`, `quantile` |
| `gateway_rate_limit_exceeded_total` | Counter | Requests rejected by rate limiter | `client_id` |
| `gateway_tls_handshake_duration_seconds` | Histogram | TLS handshake latency | `cipher_suite` |
| `gateway_dns_cache_hits_total` | Counter | DNS cache hits | |
| `gateway_active_connections` | Gauge | Active client connections | |

**Recommended Alerts**
- `gateway_http_request_duration_seconds_quantile{quantile="0.99"}` > 200ms
- `gateway_http_requests_total{status=~"5.."}` / `gateway_http_requests_total` > 5%

### Service: Location

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `location_gps_updates_total` | Counter | GPS position updates received | `vehicle_type` |
| `location_gps_update_latency_seconds` | Histogram | Latency from update reception to store | `vehicle_type` |
| `location_nearest_driver_query_duration_seconds` | Histogram | Latency of nearest-driver lookups | `zone_id` |
| `location_nearest_driver_query_duration_seconds_quantile` | Histogram quantile | P50, P95, P99 | `zone_id`, `quantile` |
| `location_active_drivers` | Gauge | Number of drivers with recent GPS (last 5 min) | `zone_id`, `vehicle_type` |
| `location_timing_wheel_pending_events` | Gauge | Events queued in timing wheel for staleness check | |
| `location_ipc_buffer_utilization` | Gauge | Percentage of shared-memory buffer used (0–100) | `buffer_id` |
| `location_driver_query_cache_hits_total` | Counter | Cache hits for driver lookups | `zone_id` |

**Recommended Alerts**
- `location_nearest_driver_query_duration_seconds_quantile{quantile="0.99"}` > 100ms (drivers waiting)
- `location_ipc_buffer_utilization` > 90% (risk of buffer overflow)
- `location_active_drivers{zone_id=~".*"}` = 0 (zone has no drivers)

### Service: Ride

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `ride_requests_total` | Counter | Ride requests received | `status` (`requested`, `matched`, `started`, `completed`, `cancelled`) |
| `ride_requests_matched_total` | Counter | Successful matches (ride → driver) | |
| `ride_requests_completed_total` | Counter | Completed rides | |
| `ride_requests_cancelled_total` | Counter | Cancelled rides | `reason` |
| `ride_dsl_compile_duration_seconds` | Histogram | Time to compile matching rules (DSL) | |
| `ride_state_transition_total` | Counter | State machine transitions | `from_state`, `to_state` |
| `ride_active_rides` | Gauge | Rides currently in `started` state | |
| `ride_matching_queue_depth` | Gauge | Pending requests waiting for match | |

**Recommended Alerts**
- `ride_requests_matched_total` / `ride_requests_total` < 0.8 (low match rate)
- `ride_dsl_compile_duration_seconds` > 50ms (rule engine is slow)
- `ride_matching_queue_depth` > 1000 (matching is backed up)

### Service: Pricing

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `pricing_surge_zone_count` | Gauge | Number of zones currently in surge (multiplier > 1.0) | |
| `pricing_surge_multiplier_avg` | Gauge | Average surge multiplier across zones | `zone_id` |
| `pricing_fare_estimates_total` | Counter | Fare estimates calculated | |
| `pricing_fare_estimate_duration_seconds` | Histogram | Time to calculate a fare | |
| `pricing_promo_redemptions_total` | Counter | Promo codes successfully applied | `promo_code` |
| `pricing_promo_invalid_total` | Counter | Invalid or expired promo code attempts | |

**Recommended Alerts**
- `pricing_surge_multiplier_avg` > 2.5 for > 10 min (sustained high surge)
- `pricing_fare_estimate_duration_seconds` > 10ms (pricing is slow)

### Service: Payment

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `payment_charges_total` | Counter | Charges attempted | `status` (`success`, `failure`, `retry`) |
| `payment_charges_success_total` | Counter | Successful charge completions | |
| `payment_charges_amount_sum` | Counter | Total amount charged (in minor currency units) | |
| `payment_charges_amount_avg` | Gauge | Average charge amount | |
| `payment_saga_success_total` | Counter | Completed payment sagas | |
| `payment_saga_failure_total` | Counter | Failed payment sagas (triggering compensations) | |
| `payment_saga_duration_seconds` | Histogram | Time from charge initiation to completion | |
| `payment_idempotency_cache_hits_total` | Counter | Requests matched by idempotency cache | |
| `payment_idempotency_cache_misses_total` | Counter | Requests not in cache (first attempt) | |

**Recommended Alerts**
- `payment_charges_total{status="failure"}` / `payment_charges_total` > 2% (high failure rate)
- `payment_saga_failure_total` / `payment_saga_success_total` > 5% (saga instability)
- `payment_charges_success_total` rate = 0 (charging is completely blocked)

### Service: Notification

| Metric | Type | Description | Labels |
|--------|------|-------------|--------|
| `notification_sent_total` | Counter | Notifications successfully sent | `type` (`sms`, `push`) |
| `notification_failed_total` | Counter | Notification delivery failures | `type`, `error_code` |
| `notification_pending_count` | Gauge | Undelivered notifications in queue | `type` |
| `notification_queue_depth` | Gauge | Notifications waiting to be sent | |
| `notification_template_render_duration_seconds` | Histogram | Time to render a template | `template_name` |

**Recommended Alerts**
- `notification_failed_total{type="sms"}` / `notification_sent_total{type="sms"}` > 5%
- `notification_queue_depth` > 10000 (notification backlog)
- `notification_pending_count{type="push"}` > 1000 (push notifications not being sent)

---

## 3. Service Health Model

Every service implements a health check endpoint that returns the health status of the service. Health checks serve two distinct purposes in production.

### Readiness Check

Endpoint: `GET /health/ready`

Returns HTTP 200 if the service is ready to accept traffic, otherwise returns HTTP 503. Used by load balancers and orchestrators (Kubernetes) to route traffic.

**Readiness Criteria** (all must be true):
- Database connections are established and responsive.
- Dependent services (via client stubs) are reachable.
- Configuration has been loaded.
- Caches are warmed or warming.

Example response:
```json
{
  "status": "UP",
  "checks": {
    "database": "UP",
    "cache": "UP",
    "config": "UP",
    "dependencies": {
      "pricing_service": "UP",
      "location_service": "UP"
    }
  }
}
```

If any check fails, return 503:
```json
{
  "status": "DOWN",
  "checks": {
    "database": "DOWN",
    "error": "Connection timeout after 5s"
  }
}
```

### Liveness Check

Endpoint: `GET /health/live`

Returns HTTP 200 if the process is alive and responsive, otherwise returns HTTP 503. Used by orchestrators to decide whether to restart the container.

**Liveness Criteria**:
- The main thread is not deadlocked.
- Memory is not critically exhausted (heap < 95%).
- No unrecoverable exceptions in the event loop.

Example response:
```json
{
  "status": "UP",
  "checks": {
    "process": "ALIVE",
    "memory": "90% (1.8 GB / 2 GB)",
    "event_loop": "RESPONSIVE"
  }
}
```

If the process is hung:
```json
{
  "status": "DOWN",
  "checks": {
    "process": "DEADLOCKED",
    "error": "Main thread blocked on lock for 30s"
  }
}
```

### Health Check Implementation

All health checks should complete within 5 seconds. For dependencies, use a cached health state with a 30-second TTL to avoid cascading slow health checks.

```
Kubernetes liveness probe: GET /health/live, timeout=5s, period=10s, threshold=3
Kubernetes readiness probe: GET /health/ready, timeout=5s, period=5s, threshold=1
```

---

## 4. Logging Strategy

All services use **SLF4J** (Simple Logging Facade for Java) with **Logback** as the implementation. Logging is structured (JSON or key=value format) to enable machine-readable search and aggregation.

### Log Levels

Define log levels per component to balance signal and noise:

| Level | Usage | Example |
|-------|-------|---------|
| DEBUG | Fine-grained diagnostic information | "Demand recorded for zone 0x87283472d9fffff" |
| INFO | Informational messages about application progress | "Promo code registered: GRAB20 (20% off, expires 2026-04-01)" |
| WARN | Potentially harmful situations | "Promo code not found: INVALID" |
| ERROR | Error events that might still allow the application to continue | "Charge failed: timeout after 5s" |
| FATAL | Very severe error events that will presumably lead to abort | "Database connection lost and cannot recover" |

### Environment-Specific Levels

**Development**
- Location service: DEBUG (GPS tracking is verbose; needed for testing)
- All others: INFO

**Staging**
- All services: INFO

**Production**
- Gateway: WARN (high volume of requests; DEBUG would overwhelm logs)
- Location: WARN (GPS updates are high-frequency; only errors/warnings matter)
- Pricing: INFO (moderate volume; allows observing surge events)
- Ride: INFO (moderate volume; state transitions are useful)
- Payment: INFO (low volume; important for reconciliation)
- Notification: INFO (moderate volume; failures are critical)

### Structured Logging Pattern

Always use structured logging with named fields to enable aggregation:

**Good:**
```
logger.info("Ride matched", Map.of(
    "rideId", "ride-12345",
    "driverId", "driver-67890",
    "duration_ms", 150,
    "zone", "0x87283472d9fffff"
));
```
Output: `timestamp=2026-03-31T10:23:45Z level=INFO message="Ride matched" rideId=ride-12345 driverId=driver-67890 duration_ms=150 zone=0x87283472d9fffff`

**Bad:**
```
logger.info("Ride matched: ride-12345 with driver-67890, took 150ms");
```
Output: unstructured, cannot be queried by field.

### Correlation IDs

Every request flowing through the system should carry a correlation ID (UUID) that is propagated through all service calls. This enables tracing a single user's flow through the entire system.

**Request Entry Point (Gateway)**
```
String correlationId = request.getHeader("X-Correlation-ID");
if (correlationId == null) {
    correlationId = UUID.randomUUID().toString();
}
MDC.put("correlationId", correlationId);  // Add to Logback MDC
```

**Outbound Service Calls**
```
client.callPricingService(
    request,
    Map.of("X-Correlation-ID", MDC.get("correlationId"))
);
```

**Logging**
```
logger.info("Fare estimated", Map.of(
    "correlationId", MDC.get("correlationId"),  // Automatically included by Logback
    "fare", 5.64
));
```

All logs with the same `correlationId` can be filtered together to reconstruct a single request's journey.

---

## 5. Monitoring Dashboard Design

Grafana dashboards provide real-time visualization of system health. The GrabFlow platform uses four primary dashboards, each answering a different operational question.

### Dashboard 1: Request Flow

**Question:** Is traffic flowing through the system, and where are the latency bottlenecks?

**Layout:** 3 rows × 2 columns

**Panel 1.1: Gateway Throughput (Top-Left)**
- Metric: `rate(gateway_http_requests_total[5m])` per `endpoint`
- Type: Time-series line graph
- Y-axis: Requests/second
- Time window: Last 6 hours
- Shows traffic volume to each endpoint (GET /price, POST /ride, etc.)

**Panel 1.2: Per-Service Latency Heatmap (Top-Right)**
- Metric: `gateway_http_request_duration_seconds_bucket` per `endpoint`
- Type: Heatmap (time on x-axis, latency buckets on y-axis)
- Y-axis: Latency (ms), buckets: 1, 10, 50, 100, 200, 500, 1000+
- Shows distribution of latencies over time; high values at the top indicate tail latency.

**Panel 1.3: Per-Service P99 Latency (Middle-Left)**
- Metric: `histogram_quantile(0.99, gateway_http_request_duration_seconds)` per `endpoint`
- Type: Time-series line graph
- Y-axis: P99 latency (seconds)
- Overlaid threshold line at 200ms (red).
- Alerts if this line crosses the threshold for >5 minutes.

**Panel 1.4: Error Rate by Endpoint (Middle-Right)**
- Metric: `rate(gateway_http_requests_total{status=~"5.."}[5m]) / rate(gateway_http_requests_total[5m])` per `endpoint`
- Type: Time-series line graph
- Y-axis: Error rate (0–100%)
- Overlaid threshold line at 5% (red).

**Panel 1.5: Service Dependency Health (Bottom-Left)**
- Metric: Up/Down status of each service from the service discovery layer
- Type: Status indicator (colored boxes: green=up, red=down, yellow=degraded)
- Shows: Gateway, Location, Ride, Pricing, Payment, Notification services.

**Panel 1.6: Request Queue Depth (Bottom-Right)**
- Metric: `ride_matching_queue_depth`, `notification_queue_depth`, `location_ipc_buffer_utilization`
- Type: Gauge (0–100% of capacity)
- Shows backpressure in the system; high values indicate the system is saturated.

### Dashboard 2: Geospatial (Ride Matching and Surge)

**Question:** Where are drivers, passengers, and surge activity?

**Layout:** 1 large panel spanning 2 columns

**Panel 2.1: Driver Density Heatmap (Full Width)**
- Metric: `location_active_drivers` per `zone_id` (H3 cells)
- Type: Map visualization (GeoJSON overlay of H3 cells, colored by driver count)
- Color scale: 0 drivers (white) → 100+ drivers (dark green)
- Opacity: Blended with surge heatmap below.

**Panel 2.2: Surge Multiplier Overlay (Blended)**
- Metric: `pricing_surge_multiplier_avg` per `zone_id`
- Type: Heatmap overlay on the map
- Color scale: 1.0x (white) → 3.0x (red)
- Allows operators to see surge hotspots relative to driver density.

**Panel 2.3: Ride Match Rate by Zone (Bottom-Left)**
- Metric: `rate(ride_requests_matched_total[5m]) / rate(ride_requests_total[5m])` per `zone_id`
- Type: Heatmap (cells colored by match rate)
- Color scale: 0% (red) → 100% (green)
- Shows which zones have unmatched demand.

### Dashboard 3: Ride Lifecycle (Funnel)

**Question:** Are rides being requested, matched, and completed at normal rates?

**Layout:** 1 large funnel visualization + supporting panels

**Panel 3.1: Ride Funnel (Full Width, Top)**
- Metrics: `rate(ride_requests_total[5m])` → `rate(ride_requests_matched_total[5m])` → `rate(ride_requests_completed_total[5m])`
- Type: Funnel chart
- Shows drop-off at each stage (e.g., 100 requests → 80 matched → 75 completed).
- Displays drop-off rate as percentage loss at each stage.

**Panel 3.2: Cancellation Rate (Bottom-Left)**
- Metric: `rate(ride_requests_cancelled_total[5m]) / rate(ride_requests_total[5m])` per `reason`
- Type: Time-series stacked area graph
- Y-axis: Cancellation rate (%)
- Stacked by reason: `passenger_cancel`, `driver_cancel`, `timeout`, `no_match`.
- Overlaid threshold line at 10% (yellow) and 20% (red).

**Panel 3.3: Average Ride Duration (Bottom-Right)**
- Metric: Custom metric `ride_duration_seconds` (sum of ride durations / count of completed rides)
- Type: Time-series line graph
- Y-axis: Duration (seconds)
- Shows if rides are taking longer on average (could indicate traffic, increased distance, or demand shift).

### Dashboard 4: Payment Health

**Question:** Are charges being processed successfully, and are there fraud signals?

**Layout:** 4 panels

**Panel 4.1: Saga Success Rate (Top-Left)**
- Metric: `payment_saga_success_total / (payment_saga_success_total + payment_saga_failure_total)`
- Type: Time-series line graph
- Y-axis: Success rate (0–100%)
- Overlaid threshold line at 95% (yellow) and 90% (red).

**Panel 4.2: Charge Volume and Amount (Top-Right)**
- Metric: `rate(payment_charges_success_total[5m])` (charges/sec) and `rate(payment_charges_amount_sum[5m])` (currency/sec)
- Type: Dual-axis graph (left = count, right = amount)
- Shows business volume and revenue in real time.

**Panel 4.3: Charge Failure Breakdown (Bottom-Left)**
- Metric: `rate(payment_charges_total{status="failure"}[5m])` per `error_code`
- Type: Time-series stacked area
- Stacked by error: `insufficient_funds`, `card_declined`, `timeout`, `network_error`.
- Helps identify systematic failure causes.

**Panel 4.4: Fraud Signals (Bottom-Right)**
- Metric: Custom metrics `payment_chargebacks_total`, `payment_suspicious_transactions_total`, `payment_duplicate_charge_attempts_total`
- Type: Gauge (0–100%)
- Single-stat visualization showing current count and trend.

---

## 6. Alerting Rules

Critical alerts wake up on-call engineers. All alert rules are evaluated by Prometheus every 30 seconds. An alert fires if the condition is true for the specified `for` duration (to avoid flapping).

### Alert Rule Table

| Alert | Condition | For | Severity | Action |
|-------|-----------|-----|----------|--------|
| **HighP99Latency** | `histogram_quantile(0.99, gateway_http_request_duration_seconds) > 0.2` | 5m | **P1** | Page on-call; check Gateway and downstream services |
| **HighErrorRate** | `rate(gateway_http_requests_total{status=~"5.."}[5m]) / rate(gateway_http_requests_total[5m]) > 0.05` | 5m | **P1** | Page on-call; check service logs for errors |
| **LocationServiceHighP99** | `histogram_quantile(0.99, location_nearest_driver_query_duration_seconds) > 0.1` | 5m | **P2** | Alert via Slack; check Location service query cache |
| **DriverTimeoutRate** | `rate(location_nearest_driver_query_duration_seconds_bucket{le="5"}[5m]) / rate(location_nearest_driver_query_duration_seconds_count[5m]) < 0.9` | 10m | **P2** | Alert via Slack; increase timeout budget or optimize queries |
| **ZoneNoDrivers** | `location_active_drivers{zone_id=~".*"} = 0` | 30s | **P3** | Log to Slack; may trigger dynamic pricing and supply incentives |
| **IPCBufferExhaustion** | `location_ipc_buffer_utilization > 0.9` | 5m | **P2** | Page on-call; scale Location service or increase buffer |
| **RideMatchRate** | `rate(ride_requests_matched_total[5m]) / rate(ride_requests_total[5m]) < 0.8` | 15m | **P2** | Alert via Slack; check Ride service and Location service for bottlenecks |
| **DSLCompilationSlow** | `ride_dsl_compile_duration_seconds > 0.05` | 5m | **P3** | Log to Slack; profile DSL compiler for hot spots |
| **MatchingQueueBackup** | `ride_matching_queue_depth > 1000` | 10m | **P2** | Alert via Slack; scale Ride service or optimize matching algorithm |
| **SurgeExtreme** | `pricing_surge_multiplier_avg > 2.5 and on(zone_id) for 10m` | 10m | **P3** | Alert via Slack; consider capping surge or triggering supply incentives |
| **ChargeFailureRate** | `rate(payment_charges_total{status="failure"}[5m]) / rate(payment_charges_total[5m]) > 0.02` | 5m | **P1** | Page on-call; check Payment service and upstream (Ride, Pricing) |
| **SagaFailureRate** | `rate(payment_saga_failure_total[5m]) / (rate(payment_saga_success_total[5m]) + rate(payment_saga_failure_total[5m])) > 0.05` | 10m | **P2** | Alert via Slack; check saga compensation logic and database state |
| **ChargingBlocked** | `rate(payment_charges_success_total[5m]) = 0 for 5m` | 5m | **P1** | Page on-call; investigate Payment service immediately (potential service-wide outage) |
| **SMSDeliveryFailure** | `rate(notification_failed_total{type="sms"}[5m]) / rate(notification_sent_total{type="sms"}[5m]) > 0.05` | 10m | **P2** | Alert via Slack; check SMS provider (Twilio, etc.) status |
| **NotificationQueueBackup** | `notification_queue_depth > 10000` | 15m | **P2** | Alert via Slack; scale Notification service or check downstream delivery blocks |
| **ServiceDown** | `up{job=~"gateway\|location\|ride\|pricing\|payment\|notification"} = 0` | 1m | **P1** | Page on-call; service is unreachable; check logs and health endpoints |

### Alert Routing

- **P1 alerts** → PagerDuty → SMS to on-call engineer
- **P2 alerts** → Slack `#alerts` channel
- **P3 alerts** → Slack `#alerts` channel (no page)

### Alert Runbook Links

Each alert should have a link to a runbook (troubleshooting guide). Example runbook locations:
- `HighP99Latency` → `/docs/runbooks/high-p99-latency.md`
- `ChargeFailureRate` → `/docs/runbooks/charge-failure-rate.md`
- `ServiceDown` → `/docs/runbooks/service-down.md`

---

## 7. See Also

- **Google SRE Book** (O'Reilly, 2016)
  - Chapter 4: "Monitoring Distributed Systems" — foundational principles of production observability.
  - Chapter 6: "Monitoring Distributed Systems" — designing dashboards and alert rules.
  - https://sre.google/books/

- **Kleppmann, M. (2017).** *Designing Data-Intensive Applications*. O'Reilly Media.
  - Chapter 1: "Reliability, Scalability, and Maintainability" — why observability is non-negotiable for reliability.
  - Chapter 9: "Consistency and Consensus" — distributed tracing for debugging consensus protocols.

- **Prometheus Documentation**
  - Best practices for time-series metrics: https://prometheus.io/docs/practices/instrumentation/
  - Alerting rules syntax: https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/

- **Logback Configuration**
  - Structured logging with Logback: https://logback.qos.ch/manual/configuration.html
  - JSON encoder for machine-readable logs: https://github.com/logstash-logback-encoder/logstash-logback-encoder

- **GrabFlow Architecture**
  - See [../README.md](../README.md) for the 7-service platform overview.
  - See [pricing-engine.md](pricing-engine.md) for pricing service observability integration.

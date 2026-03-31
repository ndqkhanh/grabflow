# GrabFlow — Architecture Trade-offs

Every architectural decision involves a trade-off. This document explains the six most consequential design choices in GrabFlow, what was sacrificed in each case, and when a different approach would be better.

---

## 1. H3 Hex Grid vs PostGIS/R-Tree

**What was chosen.** The LocationService represents driver positions as 64-bit H3 cell IDs at resolution 9 (~174m edge length). Nearest-driver queries enumerate all cells in a k-ring and execute a single range query in NexusDB. `H3Index.latLngToCell(lat, lng, 9)` is the main transformation; `H3Index.kRing(cellId, k)` returns all neighboring cells.

**What's sacrificed.**
- **Spatial indexes maturity.** PostGIS has 20+ years of battle-testing. H3 is newer, less mature.
- **Arbitrary polygon queries.** PostGIS supports arbitrary geofencing (e.g., "drivers within city boundary"). H3 supports only cells and rings.
- **Precision loss.** Resolution 9 is ~174m edge length; very high-precision locations lose ~87m of granularity. A GPS error of ±5m becomes invisible.
- **Single-cell containment.** A driver might be at a cell boundary; two simultaneous lookups at slightly different times could report different cells.

**When the alternative wins.**
- **Polygon-based matching.** If the business requires "match only drivers inside geofence ZONE_A" (an arbitrary polygon, not H3 cells), PostGIS's `ST_Contains` is simpler.
- **Sub-meter precision.** If precision matters (e.g., autonomous delivery), use WGS84 lat/lng with H3 resolution 12+ (~25m).
- **Complex spatial joins.** If queries are like "drivers whose current heading is toward the pickup location AND within radius R," spatial indexes in PostGIS are more flexible.

**Engineering judgment.** H3 cells are 64-bit integers. This enables O(1) memory per cell in a `Set<Long>` or `Map<Long, Driver>`. PostGIS GEOMETRY objects are 16+ bytes, adding memory overhead and GC pressure. At GrabFlow's scale (10,000 drivers, 1M location updates/sec), the 64-bit encoding is decisive. The `kRing(cellId, k=3)` operation is O(27 cells), amortizing to ~2µs per query. PostGIS R-Tree traversal is O(log N), where N is total indexed cells globally. For 1B cells, that's ~30 comparisons. But each comparison touches a page in the index tree, incurring cache misses. H3's O(27) is a tight inner loop, likely cache-resident.

See `H3Index.java:latLngToCell()` for the implementation.

---

## 2. Custom DSL Compiler vs SQL/Config-based Matching

**What was chosen.** Matching rules are expressed in a domain-specific language:
```
MATCH driver
  WHERE distance < 5km AND rating > 4.5 AND acceptance_rate > 0.8
  ORDER BY distance ASC
  LIMIT 10
```

The `Lexer` tokenizes, the `Parser` builds an AST, the `Compiler` emits bytecode-like instructions, and the `Executor` interprets them.

**What's sacrificed.**
- **Time to market.** Building a compiler takes weeks. SQL with a generic query optimizer ships in days.
- **Turing-completeness.** The DSL supports predicates and projections, not arbitrary logic. "Match the driver whose name is longest" requires hardcoding.
- **IDE/editor support.** No syntax highlighting, autocomplete, or static type checking for a custom DSL.
- **Community.** SQL has billions of developers and tools. The DSL is custom; only GrabFlow engineers understand it.
- **Dynamic dispatch overhead.** The AST is interpreted, not compiled to native code. Each predicate evaluation is a switch-statement dispatch.

**When the alternative wins.**
- **One-off rules.** For a 3-person startup with one matching rule, hardcoding in Java is simpler than building a compiler.
- **Arbitrary business logic.** If rules depend on external APIs (e.g., "check driver's credit score from an external service"), SQL or a scripting language is more practical.
- **OLAP queries.** If rules need aggregations (e.g., "match drivers whose avg response time is < 30s"), SQL's GROUP BY is cleaner than DSL.

**Engineering judgment.** GrabFlow has ~20 matching rules that change monthly: surge-aware matching, certification-based matching, vehicle-type filtering, acceptance-rate thresholds, predictive scoring. Hardcoding these creates unmaintainable branching. A DSL moves rules from code to data, enabling:
- **A/B testing without deployment.** Swap rules live; measure acceptance rate; commit or rollback.
- **Isolation testing.** "Does rule X match driver Y?" is a unit test with no side effects.
- **Auditability.** Committed rules are immutable; you can replay rules from 3 days ago to debug a mismatch.

The compiler compiles to a simple bytecode-like format (see `Compiler.java`), enabling single-pass evaluation with no allocations.

See `Lexer.java`, `Parser.java`, and `Compiler.java` for the full implementation.

---

## 3. Saga Orchestration vs Two-Phase Commit

**What was chosen.** Payment workflows use the saga pattern. Steps are: (1) `authorize_payment` (place hold on rider's card), (2) `capture_payment` (settle the charge), (3) `pay_driver` (transfer driver's share). Each step is a local transaction in NexusDB. On failure, completed steps are compensated in reverse order.

**Implementation:** `SagaOrchestrator.execute(sagaId, steps)` accepts a list of `SagaStep` implementations. Each step implements `execute(ctx)` and `compensate(ctx)`. The orchestrator runs steps in sequence; on first failure, it compensates all prior steps in reverse.

**What's sacrificed.**
- **Atomicity across all steps.** With 2PC, either all steps commit or all rollback. With sagas, intermediate state is visible to other transactions. Between "authorize" and "capture," a viewer might observe a pending hold.
- **Compensation complexity.** Writing a `compensate()` method that perfectly undoes `execute()` is error-prone. Refunds might fail; compensation might need retries.
- **Coordination overhead.** 2PC uses a coordinator (Paxos, Raft) to ensure atomicity. Sagas require careful sequencing and logging.
- **No causal ordering.** If step B depends on the output of step A, sagas force sequential execution. Parallel steps are harder to reason about.
- **Recovery complexity.** If the orchestrator crashes mid-saga, recovery requires replaying the saga log to determine which steps completed and which need compensation.

**When the alternative wins.**
- **Strong atomicity required.** For bank transfers where "either both accounts update or neither," 2PC with a consensus-based coordinator (e.g., etcd, Raft) is safer.
- **Few steps.** If a workflow has only 1-2 steps, 2PC overhead is small; atomicity is easier to reason about.
- **Synchronous compensation.** If compensation must complete before returning to the client (e.g., hotel booking with instant refund on cancellation), 2PC's atomic rollback is simpler.

**Engineering judgment.** Sagas excel when:
1. **Steps are slow and I/O-bound.** GrabFlow's steps (authorize ~100ms, capture ~50ms, pay driver ~200ms) are all I/O-bound. 2PC serializes these steps, adding 350ms. Sagas allow async execution: "authorize" and "capture" can overlap. Effective latency: max(100ms, 50ms, 200ms) = 200ms.
2. **Availability > atomicity.** A failed capture is rare. When it happens, automatic refund (compensation) is acceptable.
3. **Compensation is idempotent.** Refunding the same rider twice is a no-op (idempotent).

The `SagaOrchestrator.compensateAll()` method runs compensations in reverse order. If a compensation throws, the exception is logged but doesn't stop other compensations—a fail-safe pattern.

See `SagaOrchestrator.java` for the full implementation.

---

## 4. Custom Infrastructure vs Managed Services

**What was chosen.** GrabFlow builds four infrastructure systems from scratch instead of using off-the-shelf managed services:
- **NexusDB** instead of managed PostgreSQL (better concurrency model for single-node, custom MVCC)
- **TurboMQ** instead of managed Kafka (per-partition Raft, gossip membership, no centralized controller)
- **FlashCache** instead of managed Redis (Zipfian-aware eviction, memory-mapped backing)
- **AgentForge** instead of managed inference services (proprietary models, custom vectorization)

**What's sacrificed.**
- **Operational burden.** Building systems is fun; operating them at scale is not. On-call engineers wake up for NexusDB crashes.
- **Feature completeness.** Managed PostgreSQL has 25+ years of tuning. NexusDB is missing obvious features (distributed transactions, full-text search, window functions).
- **Security surface.** Each new system is a potential attack vector. PostgreSQL's security is battle-tested.
- **Hiring burden.** Recruiting engineers who understand custom systems is hard. Every graduate knows SQL; few understand H3 or MVCC.
- **Time to market.** Building infrastructure delays the product.

**When the alternative wins.**
- **Green-field product.** For a 1.0 launch, use managed services. Optimize when you have customers.
- **Polyglot requirements.** If the system must support multiple languages (Python/Node/Go), managed Kafka with client libraries is simpler than TurboMQ.
- **Regulatory compliance.** Managed services (AWS RDS, GCP Cloud SQL) have compliance certifications (SOC2, HIPAA, GDPR). Custom systems require extensive audits.

**Engineering judgment.** GrabFlow's business depends on latency and geospatial-awareness. Off-the-shelf systems add layers:
- **PostgreSQL → PostGIS:** Geospatial queries are second-class; you're fighting the system.
- **Kafka:** Per-partition ordering is strong, but the write amplification (compaction, replication, log cleanup) adds 10-30x overhead.
- **Redis:** Eviction policies don't understand Zipfian access patterns; you waste memory.

Building systems from scratch means:
1. **Align architecture with the workload.** H3 cells as the primary abstraction, not a library bolted onto lat/lng.
2. **Eliminate unnecessary layers.** Direct memory mapping for IPC (no serialization), per-partition Raft (no metadata bottleneck).
3. **Co-optimize stack.** NexusDB's MVCC version chains are stored in the same B-Tree as live data, enabling zero-copy reads.

This is a high-risk bet: if assumptions change (e.g., must scale to 100 servers), the custom infrastructure may not support it. But if assumptions hold (geospatial, single-region, sub-50ms latency), the upside is immense.

---

## 5. Bloom Filter + Token Bucket vs WAF/Reverse Proxy

**What was chosen.** The API Gateway implements rate limiting in two layers:
1. **Bloom Filter:** IP-based DDoS blocking. `BloomFilter.mightContain(ip)` checks if an IP is known-bad with 1% false positive rate. Configuration is in-memory; no external state.
2. **Token Bucket:** Per-user rate limiting. Each user has a bucket with a fixed capacity (e.g., 100 requests/sec). Requests decrement the bucket; the bucket refills over time. Implementation uses atomic counters, no locks.

**Implementation:** `BloomFilter` is backed by a `long[]` bit array accessed via `AtomicLongArray` for lock-free updates. The Kirsch-Mitzenmacher double-hashing technique uses only two hash computations per lookup. `TokenBucket` uses an atomic counter for the current token count and a scheduled refill task.

**What's sacrificed.**
- **WAF features.** Commercial WAFs (Cloudflare, AWS WAF) detect sophisticated attacks: SQL injection, XSS, bot patterns, geographic anomalies. The Bloom filter only blocks IPs.
- **Distributed state.** If the Gateway is replicated (multi-region), each replica has its own Bloom filter and token buckets. A request might slip through by hitting a different replica. A distributed cache (Redis) would be needed for global state.
- **False positives.** The Bloom filter has a 1% false positive rate. 1 in 100 legitimate IPs are blocked. (Recoverable: user retries or contacts support. Not acceptable for 99.9% availability.)
- **Adaptive learning.** The Bloom filter's set of bad IPs is static (loaded at startup). It can't learn from observed attacks (e.g., "this IP is probing every endpoint").

**When the alternative wins.**
- **Public-facing API.** For APIs exposed to the internet with malicious actors (scrapers, credential-stuffing attacks), a commercial WAF with live threat intelligence is mandatory.
- **Global rate limiting.** If GrabFlow is deployed in 50 regions and must enforce a global limit (e.g., "max 1M requests/sec worldwide"), use a central Redis or DistributedCache. Per-replica rate limiting allows 50x overage if all replicas are hit.
- **Complex rule evaluation.** If rate limiting must depend on user properties (e.g., "premium users get 10x quota"), a rule engine is cleaner than hardcoded logic.

**Engineering judgment.** GrabFlow's Gateway is behind a private network boundary (accessed only via mobile apps, not the public internet). Requests come from mobile clients with stable client IDs (not rotating IP addresses). The Bloom filter is a lightweight, single-pass first line of defense against misbehaving clients. For attacks (SQL injection, etc.), input validation in each service is more effective than IP blocking. Token buckets per user are exact (no false positives) and have O(1) complexity. The atomic counter design avoids locks, enabling millions of updates per second.

See `BloomFilter.java` for the implementation. The Kirsch-Mitzenmacher technique minimizes the number of hash computations; `murmur3Hash64()` produces both h1 and h2 in a single invocation.

---

## 6. Memory-Mapped IPC vs Message Queue for Location Data

**What was chosen.** Driver location updates flow through a memory-mapped ring buffer (`SharedLocationBuffer`), not TurboMQ. The buffer is backed by a file on disk. Writers (GPS ingestion) and readers (nearest-driver queries) both access the same OS page-mapped memory region. No data copying between processes.

**Implementation:** `SharedLocationBuffer` creates a fixed-size file and maps it into two processes' virtual address spaces:
- **Writer:** Increments the write position counter and writes driver locations into the next ring slot.
- **Reader:** Reads the current write position and consumes entries up to that position.

The ring buffer protocol is simple: wrap-around when reaching capacity, overwriting old entries.

**What's sacrificed.**
- **Cross-machine replication.** Mmap is local to a single machine. If a remote service needs driver locations (e.g., analytics cluster), you still need TurboMQ.
- **Backpressure.** If readers are slow, writers can't block—they wrap and overwrite old entries. Messages might be lost silently.
- **Exactly-once semantics.** A slow reader that falls behind the ring wrap loses entries. Kafka's replication log ensures no message is lost (if you tolerate durability cost).
- **Consumer lag monitoring.** With Kafka, you can query "how far behind is consumer X?" With mmap ring buffer, you must implement lag tracking yourself.
- **Graceful degradation.** If one reader crashes, it won't know entries were dropped. It must detect the gap in sequence numbers.

**When the alternative wins.**
- **Multi-machine georeplication.** If driver location must replicate to a remote analytics server, mmap is useless. Use TurboMQ (which is used for `driver.location` events anyway; the mmap buffer augments it).
- **Slow/batch consumers.** If a reader is slower than a full ring wrap (e.g., a batch analytics job consuming every 1 hour), use a persistent message queue. The mmap buffer would drop entries before the batch reader catches up.
- **High availability.** If the writer process crashes, the reader has stale mmap pages. The reader should detect the crash and fall back to TurboMQ.

**Engineering judgment.** GrabFlow's hottest path is "GPS update → cache update → nearest-driver query." This path runs hundreds of times per second per driver (10,000 drivers = 1M+ location reads/sec). TurboMQ's overhead is unacceptable at this scale:
- **Serialization.** Each location is marshaled to bytes (gRPC, Protobuf).
- **Raft replication.** Each write is replicated to N replicas and committed via consensus.
- **Network I/O.** Multi-process or multi-machine communication involves kernel context switches.

At 1M reads/sec, even 1µs per read (99% of reads are cache hits, so real I/O is rare) adds up. Mmap eliminates copying: a write by the GPS ingestion thread is instantly visible to the query handler thread. No serialization, no Raft, no network. The ring buffer naturally bounds memory (fixed capacity). Readers that fall behind lose old entries, but GPS data is ephemeral (locations are valid for ~5 seconds); losing 5-second-old positions is acceptable.

The tradeoff: single-machine only, no backpressure, silent message loss. But for this specific use case (hot path, ephemeral data, high throughput), the tradeoff is justified.

See `SharedLocationBuffer.java` for the implementation. The ring buffer protocol uses a memory-mapped `ByteBuffer` with atomic write position updates.

---

## Summary

| Dimension | Choice | Key Trade-off | Risk Level |
|-----------|--------|---------------|------------|
| Geospatial | H3 hex grid | Memory efficiency vs spatial flexibility | Low |
| Matching | DSL compiler | Explainability vs time-to-market | Medium |
| Payments | Saga pattern | Eventual consistency vs atomicity | Medium |
| Infrastructure | Custom systems | Operational burden vs latency | High |
| Rate limiting | Bloom + bucket | Simplicity vs false positives | Low |
| Location IPC | Mmap ring buffer | Single-machine vs cross-machine | Medium |

Each trade-off reflects a prioritization: GrabFlow optimizes for **latency and geospatial-awareness** over **operational simplicity** and **feature completeness**. This is appropriate for a ride-sharing platform where rider-to-driver matching must happen in under 50ms. Different priorities (e.g., correctness over latency) would lead to different trade-offs.

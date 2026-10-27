# Scheduled collection cycles at 20:00 Bangkok

The Linux scheduler assigns `TRUE_COLLECTION_DAY` once. October 25 at 20:00 and October 26 after midnight are cycle `2026-10-26`. Every JVM/batch/retry inherits the assignment, including runs extending past the following 20:00. Without an assignment a collector resolves the current 20:00 cycle once when it starts. Invalid assignments fail closed.

Total_Log filename date suffixes identify the assigned collection cycle. System_Log event timestamps retain actual collection time. Complete transcripts from another cycle are not current; touching an old file cannot change its cycle. The shared per-IP attempt count and Rx completion policy use the same pinned day, without a midnight reset.

Friday Full at 18:00 is assigned the upcoming evening cycle by the maintained LLDP_MapViewer scheduler. Scheduled 20:00 requests exit if a Full is active or their cycle is already covered by a successful Full; they are not replayed after it finishes. The next evening is eligible after Full ends.

Deploy the companion scheduler/helpers and this collector change together. `CollectionCycleMethodPatch.java` is a Java 8 deployment helper for transplanting only the reviewed date methods into an installed collector with additional transport patches. Verify the baseline budget implementation, unchanged methods/ZIP entries, and run the regression suites against the final JAR. Keep immutable before/candidate artifacts and install only while collection is stopped. This scheduler-scoped change does not publish a desktop version or replace pinned TrueLogs runtimes.

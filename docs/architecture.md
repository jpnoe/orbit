# ORBIT architecture and feature definition

The logging interface records more detailed data than the current analysis vector represents. This distinction is central to evaluating the prototype.

## State representation

`domain/state/StateEncoder.kt` reads Room observations and the latest biometric snapshot, then persists a `StateVectorSnapshot`. `FeatureAxes` fixes the following ten dimensions.

| Axis | Current encoding |
| --- | --- |
| Current mood | Last committed mood score divided by 10 |
| One-hour mood change | Current mood minus the earliest sample in the preceding hour, mapped from −10…10 to 0…1 |
| Latest sleep hours | Latest manual sleep duration, otherwise biometric sleep; divided by 12 |
| Resting heart rate | Latest biometric value mapped using 40–100 BPM |
| Steps today | Latest biometric step count divided by 15,000 |
| Activity load | Duration from selected activity events in the preceding 24 hours, divided by 180 minutes |
| Workout load | Duration from selected workout events in the preceding 24 hours, divided by 120 minutes |
| Supportive-use count | Number of events with SUPPORTIVE intent in the preceding 24 hours, divided by 4 |
| Coping-use count | Number of events with COPING intent in the preceding 24 hours, divided by 4 |
| Meal regularity | Heuristic derived from meal count in the preceding 24 hours; not a measure of meal timing |

Values are clamped to 0–1. Missing sources generally use 0.5 with the corresponding presence bit unset. The mood and mood-change axes currently always set their presence bits, including when mood is still the default or the one-hour sample window is empty. This needs a separate missingness decision.

Food identities, nutrient composition, specific activity categories, medication identities/doses, and arbitrary event order are not axes in this representation. They should not be described as inputs to the present clustering model. One-hour change and 24-hour aggregates provide limited temporal context, not sequence modeling.

## Encoding and analysis orchestration

`BiometricRepository.syncAndEncode()` attempts a Health Connect read, persists any resulting biometric snapshot, encodes the state, and ingests it into `TopologyRepository`. Encoding still runs when Health Connect is unavailable or denied. `encodeOnly()` uses existing local data.

`BiometricSyncWorker` requests periodic hourly synchronization. Android schedules execution; hourly configuration is not an exact sampling-time guarantee. `TopologyCompactWorker` requests weekly compaction while charging and idle.

## Neighbors and grouping

`FeatureDistance.rms()` computes root-mean-square distance over shared observed axes. Different pairs can be compared on different subsets, so missingness affects both interpretation and neighbor-search behavior.

`HnswIndex` is a custom, two-layer approximate graph index (`MAX_LEVEL = 1`). Parameters include construction search width 32 and base-layer neighbor cap 16. Eviction uses tombstones; compaction rebuilds the working graph.

`TopologyRepository` uses five non-self neighbors to estimate core distance (`MIN_PTS = 6` including the point), and uses a threshold derived from the 90th percentile of finite core distances during compaction. It groups core points using mutual reachability and connected components. This is a custom approximation, not an implementation of hierarchical cluster stability selection.

## Candidate suggestions

`NeighborhoodSuggester` first searches sufficiently large groups for a target with higher mood, then falls back to up to three higher-mood neighbors. It returns feature deltas, or a learning message when appropriate. Mood and mood-change axes are excluded from the returned deltas.

The only current call to `suggestBetterNeighborhood()` outside the repository is the synthetic debug harness. The algorithm exists; deployment of its outputs as user-facing recommendations remains unfinished. No causal or clinical efficacy claim follows from synthetic selection tests.

## Storage and communication

Room schema version 16 contains the observation and analysis tables. SQLCipher encrypts that database, and Tink / Android Keystore protect its passphrase. The last committed mood is also stored in app-private SharedPreferences; not all state is covered by the database encryption claim.

Open Food Facts requests and configured SMS shares are explicit external communication paths. Inbound SMS handling currently ignores triggers. This source does not include a hosted account or synchronization service.

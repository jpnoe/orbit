# ORBIT source review

This is a source inspection, not a full application audit or an executed Android test report. Application and analysis behavior were preserved in the prepared package.

## Confirmed implementation

- Kotlin and Compose application with Room schema version 16 and versioned migrations.
- SQLCipher database with Tink and Android Keystore passphrase protection.
- Local logging modules, history/trends, reminders, and Health Connect integration.
- Ten-axis encoding, a presence mask, approximate neighbor search, and custom density-based grouping.
- Candidate neighborhood logic and nine existing JUnit tests using synthetic data.
- A debug-only synthetic demonstration reachable through Profile.

## Priority evaluation and correctness questions

1. **Missing and stale observations.** Current mood defaults to 5 and is marked observed even before an actual log. An empty one-hour window produces an observed zero trend. The latest biometric values have no freshness cutoff in `StateEncoder`. Add explicit tests and decide how absence, staleness, and a true zero differ.
2. **Window boundaries.** Activity queries select events by `COALESCE(endTime, startTime)`. The encoder adds full durations without clipping overlap to the 24-hour window. Test events spanning the window and ongoing events that began earlier.
3. **Compaction split labels.** Each connected component independently votes for an old cluster ID. Two newly disconnected components can choose the same previous ID and subsequently appear as one group when grouped by ID. Reserve each inherited label for one component and test an actual split before claiming split correctness.
4. **Persistence consistency.** `persistAll()` writes nodes, clears/reinserts edges, clears/reinserts cluster metadata, and later updates topology state through separate DAO operations. The coroutine mutex coordinates this repository instance, but these calls are not one Room transaction. Test interruption and restart consistency.
5. **Approximate-neighbor quality.** Compare index results with exact search across insertion orders, eviction, and varied presence masks. The distance uses different shared-axis subsets for different pairs; standard metric assumptions are not automatic.
6. **Synthetic data isolation.** The debug harness inserts synthetic rows into the application's normal snapshot table and then rebuilds the same topology. Run it on a disposable installation until a separate fixture store exists.
7. **Recommendation interpretation.** The vector aggregates use counts and selected loads. It does not model specific food, medication dose, or event sequence effects. A nearby high-mood group is an association candidate, not an intervention result.

## Test coverage limits

The six suggestion tests exercise hand-constructed synthetic targets. The three harness tests exercise fixture generation, relative distance, and a permissive timing smoke threshold. They do not currently cover the state encoder, database migrations, compaction split labels, crash recovery, or graph recall against exact search.

## Changes in this package

- Added the README, architecture explanation, and these review notes.
- Kept exported Room schema JSON in the repository instead of ignoring it.
- Excluded nested build outputs and common signing-key files.
- Removed machine-local configuration, caches, and build artifacts from the distributed source archive.
- Preserved application source, dependencies, migrations, and algorithm parameters.
- Replaced a nullable test variable plus JUnit `assertNotNull` with Kotlin `requireNotNull` before numeric comparisons. JUnit assertions do not provide Kotlin nullability contracts. Test intent and the nine test cases are unchanged; execution remains pending.

## Verification limit

The Gradle wrapper failed while downloading Gradle 8.11.1 because the review environment could not reach the distribution host. The container also lacks the Android SDK. No Android build, JUnit rerun, emulator session, or device test is claimed. Run `./gradlew testDebugUnitTest assembleDebug` in an Android development environment before treating the repository as build-verified.

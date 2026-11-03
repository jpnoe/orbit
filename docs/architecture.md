# ORBIT architecture and feature definition

The logging interface records more detailed data than the current analysis vector represents. This distinction is central to evaluating the prototype.

## Configurable check-ins

Schema version 21 keeps two different kinds of check-in data, plus the older detailed logs.

`CheckInRecord` and `CheckInAxisValue` are state observations. `CheckInAxis` defines a named 0–10 scale. Stable default axes are mood, energy, confidence, and anxiety. The check-in screen saves explicit values with trigger `manual`. An untouched slider is not saved. When the mood axis is explicitly set, the same Room transaction also inserts a `MoodSample` with source `check_in`. The committed mood, which lives in preferences rather than Room, is updated only after that transaction succeeds. If that cache write fails, the check-in is still saved and the app still leaves the context screen. The failure is logged rather than shown as a failed check-in. The next launch copies the latest `MoodSample` back into the cache when that sample is at least as new as the stored commit. An untouched mood axis inserts no mood sample and does not change the committed mood. Other axes are not copied into `MoodSample`.

Next on the mood log opens the context overview without writing. Save on that overview writes the check-in, the activity, workout, usage, and diet reports, the legacy mood sample, and `completedAt` together. Cancel on the mood log or the context overview leaves the whole check-in, writes nothing, and clears the sliders, note, emotion, and logged time. Back from a category returns to the overview without writing.

`CheckInContextWindow` is the interval the person was asked about, ending at that check-in's `recordedAt`. Its start is snapshotted once: the previous same-day check-in, otherwise that day's logged wake time when the wake is not after the check-in, otherwise the local start of the day. A later backdated check-in does not rewrite a window already stored. `previousCheckInId` is cleared if that earlier check-in is deleted, and the stored start time stays. Each window has four `CheckInContextCategory` rows — activity, workout, diet, and usage — created as `none`. `CheckInActivityItem` records activities attributed to that interval, with the displayed name kept as a snapshot and `reportedAt` marking when the report was saved. `CheckInWorkoutItem` does the same for workouts and also stores an approximate duration in minutes. One row is one workout, so the same type can appear more than once. `CheckInUsageItem` records supportive or coping use in that interval. `intent` is `SUPPORTIVE` or `COPING`. `name` is a snapshot, `substanceOptionId` is a nullable reference with no foreign key, and `amount` is optional text for the whole interval, such as "10 mg" or "2 drinks." The same option is stored once; repeated use is summarized in the amount. `CheckInMeal` is one eating occasion in the interval. `size` is one of `snack`, `small`, `medium`, or `large`, a subjective description for that person, not a calorie or universal portion. `sortOrder` is the order they entered, not a clock time. `CheckInMealFoodItem` optionally names foods in that meal. `foodName` is a snapshot, `foodOptionId` is a nullable reference with no foreign key, and the same option may appear in another meal but not twice in one meal. A meal with no food items is still a valid observation. These items do not invent an occurrence time. Saving an empty activity, workout, usage, or meal selection stores status `none` and no placeholder item.

The context overview is where activity, workout, usage, and diet are edited before anything is stored. Each category starts as `none`. Opening one adjusts its list, and Save stores that list as it stands: an empty list stays `none`, and one or more items becomes `entries`. Those choices stay in memory until the overview Save. A selected usage option starts from its `defaultAmount`, and that amount is stored only when the overview is saved. Contextual foods are not analysis axes. `completedAt` is set by that same Save. It marks that the overview was saved, not that every category had items. Save returns to the check-in screen and removes the context screens from the back stack.

Detailed `ActivityEvent`, `WorkoutEvent`, meal, sleep, and usage logs remain a separate logging path. A contextual workout is an approximate report inside the check-in interval. A `WorkoutEvent` is an exact log with start, end, mood, and the other session fields. A contextual usage item says something was used during the interval. A `SubstanceUseEvent` claims a particular time and keeps its older event fields. A contextual meal is an approximate report of an eating occasion inside the check-in interval. A `MealEvent` is a separate log with an exact timestamp, weights, and optional nutrient data. Replacing contextual items does not modify `ActivityEvent`, `WorkoutEvent`, `SubstanceUseEvent`, or `MealEvent` history, and older events are not copied into the contextual tables. The detailed activity, workout, use, and diet screens still have their own event fields. The detailed diet screen still has barcode lookup, food search, and nutrient calculations.

The fixed ten-axis state vector is unchanged. `FeatureAxes`, `StateEncoder`, `StateVectorSnapshot`, neighbor search, grouping, and neighborhood suggestions still use the same representation and do not read configurable check-in rows or context windows.

Translating configurable axes into a versioned analysis schema is future work. Until that schema exists, these rows are observations and interval reports only.

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

Room schema version 21 contains the observation and analysis tables, including configurable check-ins, their context windows, and contextual activity, workout, usage, and meal items. SQLCipher encrypts that database, and Tink / Android Keystore protect its passphrase. The last committed mood is also stored in app-private SharedPreferences; not all state is covered by the database encryption claim.

Open Food Facts requests and configured SMS shares are explicit external communication paths. Inbound SMS handling currently ignores triggers. This source does not include a hosted account or synchronization service.

# Orbit Personal State-Space Navigator: North-Star Research Direction

This note is a research direction, not implemented architecture. It is learner-agnostic. It does not commit to a neural network, a clustering method, or a latent dimensionality. Its job is to organize the work already in the tree and the experiments that may follow.

Nothing in this note changes a protocol identifier, a frozen baseline, or a stored schema. Where a current contract disagrees with the direction, that disagreement is recorded under [Notes from the current contracts](#notes-from-the-current-contracts) and left unresolved.

## North star

Orbit is a personal state-space navigator that learns a personal semantic state-transition manifold. Numeric states, temporal events, and word-based concepts acquire meaning from their multiscale relationships. Orbit ultimately maps recurring contextual states, learns natural and activity-conditioned motion through that space, and supports uncertain navigation toward a chosen axis direction or target region.

Named clusters are not required. A phrase such as “creativity plateau” is a conversational example of a recognizable region, not an assumed product label.

## Observational ontology

Three typed objects carry the observations.

1. **State occurrence `Sᵢ`.** One check-in at its timestamp. It holds mood, energy, confidence, and anxiety; emotion observations; and the observation or conflict status of those readings. A missing slider stays missing. A recorded 0 stays present. A conflicting axis stays conflict, distinct from an ordinary missing reading. Anxiety is not inverted.

2. **Transition occurrence `Tᵢ`.** The interval between state occurrences. It holds activity, workout, food, usage, and medication or sleep evidence when that evidence is actually available, together with quantities, duration, start basis, boundaries, and review status. It holds a starting state and an ending state only where those endpoints were legitimately recorded. A wake or day-start interval keeps its ending state and does not receive an invented start.

3. **Shared concept token `Cⱼ`.** A logical shared concept identity such as Numb, YouTube, coding, or chili dog. The identity retains its concept type, such as emotion, activity, food, workout, usage, or medication. It connects to every occurrence where that identity was genuinely observed. Whether it is stored as a graph node, a typed relation, an embedding-table entry, or another structure remains an open implementation decision.

The temporal backbone alternates:

`… → transition → state → transition → state → transition → …`

Concept tokens connect repeated observations across that backbone. They are not a third kind of time step.

## Anchored uniform recursion

Layer zero contains the raw observations.

- `S⁰` is the raw slider, emotion, and time evidence on a state occurrence.
- `T⁰` is the raw behavior, duration, quantity, and boundary evidence on a transition occurrence.

Deeper layers contextualize both types through neighboring objects of the opposite type. A state gains meaning from the transitions that arrive and leave. A transition gains meaning from the states that start and end it.

Recursion stays finite. A depth limit or an elapsed-time radius stops the regress. Left and right radii stay independent of each other. A wider radius can characterize a waking segment, a plateau, a trend, a recovery, or a longer arc. A wake or day-start boundary keeps waking trajectories separate. The walk does not invent continuity across that boundary.

## Personal semantic latent space

The intended space is larger than four dimensions. The four sliders are grounding measurements and interpretable gradients inside a learned representation. They are not assumed to be the coordinates of the space.

State occurrences, transition occurrences, and shared concept tokens may all receive contextual embeddings. Word meaning is personal and distributional. A concept acquires meaning from the slider configurations, temporal shapes, behaviors, timing, and outcomes that surround its occurrences.

A concept may carry:

- a general personal representation;
- context-conditioned representations of particular occurrences;
- more than one mode;
- an uncertainty cloud governed by how much evidence exists.

A rare or unseen concept stays weakly placed, inherits only an explicit prior, or stays unplaced. Absence of a confident position is an acceptable result.

A two-dimensional atlas is only a drawing of a higher-dimensional representation.

## Atlas, locator, and navigator

Three roles stay distinct.

- **Retrospective atlas / teacher.** May use audited past and future evidence to discover the personal landscape.
- **Causal locator / student.** Estimates where the person is now, using only information available at that time.
- **Navigator.** Learns activity-conditioned motion from comparable starting contexts and answers goal-directed queries.

Future information stays out of live placement, behavior selection, causal matching, and recommendation. A retrospective object may describe the past. It does not become an input to the locator or the navigator by being nearby in the code.

## Soft regions and multiscale membership

Regions are soft, overlapping, and allowed to depend on scale. A focal occurrence can belong partly to several regions, or show several morphologies at once.

Each membership keeps:

- membership or support;
- evidence reliability;
- coverage and censoring;
- stability across neighboring scales;
- phase relative to a turn or an arc.

A descriptive support does not have to sum to one. A predictive mixture weight may need its own normalization. Those two numbers are different objects.

## Hierarchical Null trajectory

A future candidate Null is a partially pooled hierarchy:

- a frozen global, start-state, and horizon baseline;
- a circadian and time-since-wake residual structure;
- a weekday or day-type residual structure when the evidence supports it;
- soft region-conditioned residual experts.

A sparse expert shrinks toward the broader baseline. Overlapping region contributions are not added together as if they were separate people. Double-counting is a defect in the candidate, not a detail to tune later.

Two trajectory kinds stay separate:

- a retrospective regional average, used to describe the landscape;
- a past-only predictive Null, used when a residual is attributed to something.

The Null used to evaluate a behavior does not receive that behavior’s identity. Any contextual successor to `null-trajectory-learner-v2` has to beat that frozen learner under walk-forward evaluation before it replaces it. Until that comparison exists, `null-trajectory-learner-v2` remains the baseline.

## Activity-conditioned motion

A contextual transition is a transformation between contextual states:

`ending state ≈ dynamics(starting state, transition, horizon)`

An activity-associated residual is observed movement minus the appropriate past-only Null trajectory.

Three statements stay distinct, and none of them is automatically a causal claim:

- a behavior is commonly present in a region;
- a behavior precedes movement toward a region;
- the evidence suggests an activity altered the movement that was expected.

A goal may be:

- an axis direction, such as increased confidence;
- a selected historical exemplar;
- a target neighborhood;
- a multi-axis preference with explicit tradeoff constraints.

Navigation research begins with one step. Multi-step paths and active-learning suggestions wait.

## Circularity and audit rules

A joint atlas may include behavior, because behavior is part of lived context. The audit rules that keep that inclusion honest:

- an association claim masks or holds out the behavior being evaluated;
- a predictive effect estimate uses only information available before the transition;
- retrospective membership that was defined with future evidence enters live use only through a recognizer that sees the past alone;
- a feature that defined a region is presented as a defining feature, not as a rediscovered association;
- unknown or unreviewed behavior is never stored or summarized as absence;
- any scaler, vocabulary statistic, embedding, region definition, or learned parameter used in walk-forward causal or predictive evaluation is itself fit only from history available before that fold. A representation trained on later history stays non-causal even when the final outcome is masked.

Reviewed emptiness, where a category was opened and nothing was listed, remains a real observation. It is not the same status as unknown.

## Learning strategy

Complexity of the representation is not a reason to start with a neural network.

Transparent baselines come first. Typed co-occurrence, matrix factorization, a spectral method, or another auditable joint embedding are the kind of baseline meant here. Candidate objectives include:

- masked slider reconstruction;
- masked concept reconstruction;
- next-state prediction;
- transition reconstruction;
- discrimination between a genuine neighborhood and a shuffled one.

A heterogeneous temporal graph encoder, or a graph autoencoder, is a later candidate if the recorded history can support it and if it beats the simpler baselines on held-out tasks.

## How existing work fits

The pieces below are foundations. They do not yet implement the navigator, the joint semantic space, or soft regions.

- **Frozen state and Null foundations.** `check-in-analysis-v2` is the observational dataset. `null-trajectory-learner-v2` and `null-trajectory-gate-v2` are the locked shallow baseline. The official scored residual is the actual ending value minus the bounded 0–10 forecast. Common support is Persistence, Recent mean, and Step momentum. Behavior identity is already withheld from that learner. This note does not retune it.

- **Causal predecessor representation.** `causal-predecessor-neighborhood-v1`, `causal-predecessor-feature-view-v2`, and `predecessor-context-learner-v2` describe what was available before a focal state. They are past-only inputs toward a locator. They are not an atlas, and they are not a navigator.

- **Retrospective successor representation.** `retrospective-successor-neighborhood-v2` walks forward from a recorded transition, stops at a wake or day-start without adopting the far-side check-in, and keeps sampled windows separate from that ordered path. It is retrospective and contains future information.

- **Multiscale bidirectional evidence.** `retrospective-multiscale-bidirectional-evidence-v1` places independent backward and forward radii around one focal check-in and keeps every span visible. It does not choose a scale. Out-of-span endpoints stay interval identity and are not state members.

- **State-shape diagnostics and evidence.** `retrospective-state-shape-diagnostics-v1` reports raw measurements. `retrospective-state-shape-evidence-v1` separates local focal-segment geometry from across-waking-segment level change and lists opposite-arm turn candidates without choosing one. This work contributes region coherence, morphology, representativeness, and multiscale evidence. It does not assign a region, a support score, or a preferred scale. For this direction it is frozen pending integration: further shape math waits until a later experiment has a stated place to consume it.

- **Behavioral-association interval classification.** `behavior-association-v2` supplies a reviewed, present, absent, and unknown classification and the support bookkeeping around it. Those pieces are reusable. A state-type behavioral profile transposes the conditioning: soft state-type membership, then reviewed behavior prevalence and enrichment inside the type versus outside it. The existing behavior-first before, after, and change view stays valid as its own view. `behavior-context-balance-v1` and `behavior-exposure-episode-v1` describe context and contiguous exposure beside that classification. Nearest-opposite is a contextual matching utility. It is not a distance between state types.

- **State map.** The debug State map is an earlier PCA of check-in features. Those features include behavior presence. It is a visualization of one fit on the current history, not the joint semantic model, and the association pane does not read its coordinates.

## Conservative next experiments

These are the next experiments, in order. None of them is authorized by this note.

1. Inventory state occurrences, transition occurrences, and concept-token vocabularies, and record how often each is observed.
2. Construct an auditable typed occurrence graph for the alternating backbone, and record concept identities without choosing their physical representation.
3. Define a minimal joint signature that may include behavior, together with an explicit mask for the behavior under evaluation.
4. Fit a simple joint-embedding baseline and keep the fit inspectable.
5. Audit nearest state occurrences, nearest transition occurrences, and nearest concept tokens.
6. Test masked reconstruction and next-state prediction on held-out history.
7. Only after those audits, evaluate clustering, a constellation projection, regional Null experts, or navigation.

## Explicit non-goals

- No clinical diagnosis and no clinical state labels.
- No forced named clusters.
- No assumption that a two-dimensional projection is the underlying space.
- No causal claim from co-occurrence.
- No future leakage into live placement, matching, or recommendation.
- No recommendation without uncertainty and support.
- No automatic replacement of a frozen baseline.
- No requirement that a rare concept receive a confident position.

## Open questions

These decisions stay open.

- Whether a shared concept identity is stored as a graph node, a typed relation, an embedding-table entry, or another structure.
- Whether contextual aggregation stays deterministic or becomes learned message passing.
- Whether a representation uses one fixed radius or concatenates several scales.
- Whether meaning stays personal-only or may take an optional generic language prior.
- How much history is enough, and where partial pooling is allowed.
- Which embedding objective and which distance are the evaluation standard.
- How a transition representation should act as a transformation rather than as another point.
- How scale stability is measured.
- How a descriptive regional Null stays separate from a predictive regional Null.

## Notes from the current contracts

These are tensions with the tree as it stands. They do not close the open questions.

The alternating graph already separates state nodes from transition nodes, and a wake or day-start already stops an ordered walk without adopting the next waking check-in. That matches the backbone and the boundary rule. The transition’s stored key is still the ending check-in’s id. The two objects stay distinct only while consumers respect the key types. A joint graph that uses the raw string as one node id would collapse them.

Layer-zero transitions do not yet carry every evidence kind named above. The analysis interval has activity, workout, diet, and usage. Contextual usage, with supportive or coping intent, is the medication-like evidence inside that interval. A timed substance event, a sleep log, and wearable sleep stay on other records and are not copied onto the state or the interval. A token inventory that lists medication or sleep has to say which record it is reading. Meal size is already a behavior identity and is easy to drop if the token types are copied too narrowly.

Emotion is a name on the state occurrence. Behavior identities live on the interval. There is no shared logical concept identity that links one observed name to later occurrences. `Cⱼ` names that identity. Its physical representation stays open. The behavior catalog supplies stable keys for catalogued behavior options, but it does not provide a unified concept identity or representation spanning behaviors, emotions, and other evidence types.

Unknown and unreviewed categories are already withheld from absence in `behavior-association-v2`. Reviewed `none` is a positive observation that the category was empty. The audit rule about unknown matches the code. Collapsing reviewed emptiness into unknown would discard that observation.

The implemented predecessor and successor walks stop for a boundary, a fork, a missing link, or the end of history. They are not yet a uniform recursion of embeddings. The bidirectional spans are the existing independent radii. Shape evidence reads those spans and does not select one.

`null-trajectory-learner-v2` has no circadian layer, no weekday layer, and no region expert. The hierarchy in this note is a candidate successor. It does not replace the frozen learner by being described here. Step momentum inside that learner is a change in slider points, not a time-normalized dynamics model.

The State map’s features include behavior presence. Using that map as the space in which regions are discovered, and then reporting those same behaviors as associations, would violate the hold-out rule. The behavior-first before, after, and change view remains a separate valid view. A state-type profile reuses the classification and support counts with the conditioning transposed: membership first, then prevalence and enrichment of reviewed behaviors inside the type versus outside it. Nearest-opposite matches contexts. It does not measure distance between state types.

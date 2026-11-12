# Mishneh Torah natural-language search — QA status (11 Nov 2026)

## Scope and safety
This is a **source-only development branch**. It is not a release and must not be merged as a verified 100/100 solution. The user instructed **DO NOT COMPILE AN APK YET**. **Kotlin typechecking and Robolectric JVM unit tests are allowed here; neither packages an APK.** The original APK workflow is restricted to another branch; the current branch runs Python QA plus Kotlin/JVM source-only tests without Android APK assembly.

## Ground truth that has actually been verified
- 100 unique, tiered free-form Hebrew questions, including 20 new complex conversational paraphrases.
- 100/100 specified section/chapter/law sources physically exist in the corpus (15,063 laws). This is an **integrity test of gold labels**, NOT retrieval accuracy.
- 20/20 pre-specified answer-phrase anchors occur in the exact original source law. This is **anchor validation**, NOT the accuracy of the actual highlighted span.
- Python-only source validation run 43090673753: SUCCESS.
- Separate source-only tests cover eight time-boundary expressions (late vs before deadline) and original-text offset extraction for Shema 1:13; Python local pass. The follow-up CI execution must be checked independently.

## Known unresolved failure
The last fully logged real Android benchmark **before these search-code changes** passed only 56/110 top-1 sources, and 54/110 highlights. Median 3,249 ms; p95 10,300 ms; max 30,531 ms. Run 43052457033. This result is NOT a measurement of the current uncompiled changes.

An independently written approximate Python diagnostic retrieval mirror scored between 59 and 65 of the 100 current gold questions depending on experimental heuristics. It is **NOT the Kotlin implementation and NOT a pass of the acceptance gate**. This unsatisfactory proxy result is a reason to keep investigating.

## Implemented in this branch (uncompiled)
- Differentiate a deadline already passed from an action occurring before the cutoff, including conversational Hebrew.
- Colloquial synonyms and Hebrew prefixes; remove boilerplate question words.
- Corpus-only title/section/chapter-lead retrieval that does not map fixed questions to source IDs. Prevent one incidental title word from dominating.
- Passage evidence ranking; support quantity questions based on what is being counted; prefer a general law's opening for genuinely introductory questions.
- Detect source-backed late-time decision clauses; select verbatim original UTF-16 ranges for turquoise highlights.
- Refine long, weakly punctuated laws by scoring smaller evidence windows.
- Preserve more concepts from long natural-language queries in the local embedding lookup (32 tokens).
- Debug-only 100-question top-1/answer-highlight benchmark assets and entrypoint for later device testing.
- Python-only CI pipeline and source-gold validation; this branch must not run Gradle or an Android emulator.

## Mandatory remaining acceptance work (not completed)
1. Verify whether proposed gold labels are genuinely the best answer passages, particularly ambiguous references; do not count a merely nearby source as correct.
2. Add independent, expert-approved exact answer spans for all 100 questions (only 20 phrase anchors currently exist).
3. Perform real **Android** Top-1 and highlight tests for all 100 questions only when compilation is later authorized.
4. Fix all failures using generic source-grounded improvements, not by encoding answer IDs from the benchmark.
5. Benchmark cold start, warm search median/p95, memory, offline first-launch, crashes, and source span precision.
6. Only after the user separately authorizes compilation: build APK, install, test, capture actual screenshots, and prepare Drive delivery.

**Never call this 100/100 search accuracy until all 100 independently evaluated app results and exact source-highlight spans pass in a recorded, reproducible runtime run.**
## Continuation update — advanced retrieval research and fixes (11 Nov 2026, later)

The user authorized exploring smaller local models and auxiliary open-licensed language tools, but **did not authorize Android compilation yet**. No APK was built from this branch.

### External candidates researched
- [alphaedge-ai/multilingual-e5-small-heb-32768](https://huggingface.co/alphaedge-ai/multilingual-e5-small-heb-32768): Hebrew-trimmed multilingual E5; MIT; 34.2 million parameters; model repository ~139 MB, weights ~137 MB. Candidate for future INT8 quantization if measured accuracy, memory and device latency justify it. Do not claim that trimming has been verified on rabbinic Hebrew.
- [dicta-il/dictabert-tiny-joint](https://huggingface.co/dicta-il/dictabert-tiny-joint): CC BY 4.0; ~45 million parameters. This is a Hebrew morphological/lemma/segmentation model, **not** a trained sentence retrieval model. It may help query analysis, but download/execution/Android deployment have NOT been verified.
- [dicta-il/neodictabert-bilingual-embed](https://huggingface.co/dicta-il/neodictabert-bilingual-embed): CC BY 4.0; approximately 400 million parameters. Useful experimental comparator, not preferred for a lightweight offline Android install.
- Do not redistribute or incorporate any additional copyrighted commentary or corpus without verifying version-specific commercial rights.

### Actual changes committed
- Query morphology and evidence-window masks are now prepared once per search instead of once for every passage, and exact primary words bypass expensive fuzzy loops. **Speedup not yet benchmarked on device.**
- Title-heading coverage now counts distinct synonym-supported topic concepts and avoids elevating unrelated topics on one incidental word.
- Debug QA now separately reports *goldAnchors* and *verifiedAnchors*. Mere in-bounds turquoise span is not considered proof of answer accuracy. The gold reference assets now contain **100/100 specified answer anchor phrases**, with 80 additional anchors matched against the known 15,063-law local corpus and 20 previously verified in source-only CI. This proves source-phrase existence, not that the model highlights those phrases.
- Added independent 100-question model experiment at `scripts/experiment_tiny_hebrew_e5_100.py`, with workflow `.github/workflows/mishneh-e5-tiny-retrieval.yml`. The experiment uses Python only; it neither builds an APK nor changes Android dependencies.
- Experiment Actions run: https://github.com/AM246897531/virtual-garden-3d/actions/runs/43096714626 . At last inspection it remained queued. **Do not infer a result from a queued workflow.**
- Previous source-only QA run 43095038196 completed its core source-integrity tests but its artifact-upload step failed / final run status appeared stale. Earlier Python-only reference run 43090673753 succeeded. These tests are not retrieval accuracy.
- Pure lexical TF-IDF research on the official 100 reference questions, using an earlier 15,063-law corpus copy: best untuned lexical/heading fusion 24/100 Top-1, compared with a separate approximate Kotlin-search mirror of 64/100. Neither measures the updated actual Android runtime. Different local test-set variants must not be conflated.

### Next empirical gate (unmet)
1. Await the experimental model's actual recorded recall@1/5/20 results; model must demonstrably outperform existing retrieval and fit an Android resource budget before packaging.
2. Evaluate selected model on **previously unseen phrasing** as well as the visible 100, to avoid benchmark memorization.
3. Collect independently checked target passages for all 100 questions, rather than assuming any in-bounds highlight is correct.
4. Instrument real Android Top-1, top-k, span accuracy and latency **only after user separately authorizes compilation**.
5. No 100/100 claim, no Drive release until runtime and answer-span evidence exist.

### Fully specified 100-case turquoise QA assets — latest
- `scripts/quality_natural_100.json` and `app/src/debug/assets/quality_natural_100.json` now each have 100 explicit expected answer snippets. They are **source-sourced gold anchors** and were selected by reading the corresponding actual Rambam law.
- Added bounded span requirement: `0 < original_utf16_end - original_utf16_start <= 360`. Avoid using whole long laws as supposedly accurate highlights.
- `scripts/verify_android_100_accuracy.py` is the future hard acceptance gate: it requires all 100 individual Android cases, exact expected source, matching gold answer snippet and bounded source highlight. It is **not run on a new Android build** because compiling is still disallowed.
- The 100 anchors are minimal independently selected answer evidence. For complex multi-part questions a single anchor cannot, by itself, prove every aspect of the answer is highlighted; full expert checks and additional independent unseen test cases are required before claiming truly comprehensive accuracy.
- This update did **not** compile, install or upload an APK. The pending Python-only runs may be delayed by GitHub Actions and must be checked for actual success before attributing scores.

## No-APK high-recall branch upgrade — latest verified state

**Source-only retrieval tests, actually executed and reproducible:**
- Pre-existing 5.8MB RAMSEM01 embedding model: in a standalone local 100-question nearest-neighbor test, exact expected source appeared Top-1 in 4/100 questions and among 240 in 61/100. This is *not* the combined Android result.
- New distinct Hebrew character trigram TF-IDF index: `RCHAR301`, 15,015,182 bytes, 15,063 laws, 7,258 ngrams and 2,469,737 compressed postings. Generated from the licensed corpus; no external text needed at query time. It was independently decoded and compared with sklearn ranking (22/100 Top-1, 94/100 Top-240).
- Combined **candidate union** with five general language-to-topic vocab bridges returns 100/100 gold sources within the retrieved candidate set (not top-ranked). Extra 30-question set returned 30/30; only 17 of those queries were unique relative to the main 100. Source-only GitHub Actions run [43103664246](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43103664246): SUCCESS and includes exact printed metrics.
- After narrowing bridge triggers to avoid false topics (e.g. "ערב" vs "רב", blessings vs kosher-food identification, environment vs social influence), [GitHub source QA run](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43104394119) was SUCCESS. The latest tightened trigger changes have a subsequent run, which must be checked separately.
- All 100 expected anchor phrases exist within the actual law texts. Separate UTF-16 derivation generated **100/100 exact source anchor start/end offsets**, maximum expected anchor span 94 original UTF-16 chars, after normalizing niqqud and original punctuation. Independent Python-only [run 43104816340](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43104816340): SUCCESS.
- Offline source phrase + character-recall assets are prepared for inclusion in any *future authorized* APK. **None of this branch was compiled into a new APK**.

**Actual production Kotlin work committed (uncompiled, not runtime-verified):**
- `CharTrigramIndex.kt` loads the compact binary asset and returns independent document candidates, without network or mutable databases.
- `CorpusStore.kt` loads the index as an optional fallback-compatible companion to existing semantic/FTS search.
- `PreciseEvidenceSearch.kt` combines original semantic + FTS + char candidates. An optional bridge route is accepted only when a source passage contains independent evidence for the expanded concepts; it also refines candidate highlight windows from verbatim text.
- `SearchLanguage.kt` implements conservative meaning bridges with no benchmark gold source ID or destination citation, and contains guards against unrelated activation.
- `MainActivity.kt` debug-only 100-question quality gate now checks actual source UTF-16 offsets: it requires >=90% of independently selected gold phrase range to be covered, >=20% precision of the colored span, a <=360-character bound, exact expected Top-1 source, and a literal phrase match.
- `scripts/build_gold_answer_offsets.py` regenerates debug-only gold test positions from source text. The gold file is **never used by the production search**.
- `scripts/build_char_trigram_index.py`, `scripts/test_char_trigram_100.py`, `scripts/test_char_trigram_bridges_100.py` cover binary schema and finite source-retrieval regression.
- All current CI executions for this feature branch are **Python-only**. Changes to `.github/workflows/android-mishneh-torah.yml` merely prepare the future build recipe and do not trigger an APK compilation on this branch.

**Important limitations and do-not-misreport gates:**
- The 100/100 and 30/30 figures above mean *the correct label is present in the candidate pool*, NOT that it was first or precisely highlighted.
- The official actual Android Top-1/anchor test has NOT run on these new uncompiled changes. It remains UNKNOWN; the last old-branch benchmark was 56/110 Top-1 and 54/110 highlights.
- The answer-span gold annotations are short phrases. Even gold-offset overlap does not conclusively judge multi-part halachic answers without expert review. Never claim infallibility on arbitrary real user questions.
- The separate small Hebrew E5 experiment [run 43096714626](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43096714626) remained queued at last inspection. Do not infer that the model has been downloaded, measured or integrated.
- The user last said **DO NOT COMPILE YET**; respect this until reversed explicitly.

### Additional local, no-APK experiments: failure-aware guardrails
- Independent LightGBM learning-to-rank diagnostic using the new text/section/semantic retrieval features: one model reached **79/80 on training questions but just 11/20 on held-out questions**. This is overfitting; **it was NOT integrated** into production code.
- Naive ngram-cosine sliding answer-window experimental selector included only ~39–40 of 100 predeclared answer anchors. **It was NOT integrated**; exact turquoise answer-span localization is still a separate unsolved quality problem.
- A source-only original-text offset generator is committed. It correctly mapped all 100 gold anchor phrases to absolute original UTF-16 positions, but an independently annotated short phrase is only a minimum required part of the answer, not a guarantee about the completeness of answers with multiple conditions.
- Tightened teacher/father, food-identification, and social-influence bridge activation against false hits from words sharing substrings. Run [43105016186](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43105016186) succeeded on Python-only 100+30 source recall with negative trigger tests.
- Source-only fallback highlighting was improved further: when the main query has no direct match in a source law, bridge vocabulary can help select a real verbatim passage even if the search highlight cache is empty. This code remains **uncompiled** per user requirement.

**The key unsolved question is now precision ranking, not raw candidate coverage. Never silently count 100/100 candidate recall as 100/100 user-facing search results.**


## 12 November 2026 — real Kotlin JVM execution without APK

**This section supersedes earlier references to this branch being Python-only or not Kotlin-compiled.** The user's prohibition is on a new **APK**; the isolated branch uses **Kotlin source compilation, JVM tests and Robolectric Android framework shadows**, not `assembleDebug` and not Android packaging, installation or distribution.

### Reproducible CI and artifacts
- GitHub repo: `AM246897531/virtual-garden-3d`; experimental branch `feature/mishneh-torah-search-100qa`. Do not merge into the APK-build branch without explicit user authorization.
- Real Android/Kotlin **typechecking** succeeded: [Actions 43192736354](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43192736354). An actual Kotlin compiler previously found and prompted fixes for two illegal Compose delegated-property smart casts in `MainActivity.kt`, missed by tree-sitter syntax checks.
- Real JVM Hebrew query tests: `app/src/test/java/app/mishnehtorah/HebrewQueryRegressionTest.kt`, in `.github/workflows/mishneh-kotlin-typecheck-only.yml`. They detected a bug in normalized Hebrew forms `אם`→`אמ` and `אין`→`אינ`; it was corrected, including prefixed `ואם` and `ואין`. Do not confuse a language-test pass with retrieval accuracy.
- Successfully launched **real** `CorpusStore.search`, `PreciseEvidenceSearch`, `SemanticIndex`, `CharTrigramIndex` and actual source-offset `relevantPassage` in **Robolectric Android JVM** using the complete generated SQLite corpus, no APK: [Actions 43194392361](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43194392361). The first ten strategically sampled natural questions returned **4/10 correct Top-1 sources, 3/10 also containing the expected answer phrase**, and 10/10 spans were within legal text bounds. These numbers are a small diagnostic sample and show the new Kotlin implementation is **not yet accurate**.
- Test class: `RambamRealEngineSmokeTest.kt`, with `org.robolectric:robolectric:4.14` and API 35/JDK17, plus independently compiled test `RambamRealEngine500Test.kt`. Workflow `.github/workflows/mishneh-robolectric-no-apk.yml` now runs **strict 500-case actual Kotlin** tests, captures original UTF-16 gold-span overlap, per-cohort metrics and latency, and checks no `*.apk` was produced.
- Real Kotlin 500 test is run through [Actions 43195016714](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43195016714); **read actual results and CI conclusion**, do not assume it passed. Even a 500/500 test result would require diverse independently authored questions and real-device verification before a universal claim.

### Ground truth, biases and ongoing bottleneck
- `scripts/build_quality_500.py` produces **140 pre-existing free-form questions + 360 transparent source-derived synthetic cases** from 77 topic areas, 412 non-opening laws. A 500/500 integrity result is not 500 independent paraphrased questions.
- Binary char3 index + topical bridges achieved **500/500 candidate recall**, not Top-1. A separate Python char-only approximation measured **395/500 Top-1** and **364/500 original-phrase overlap**, but 359/360 synthetic sources were trivially literal, while just **36/140** manually authored sources were Top-1 and **11/140** had acceptable overlapping highlights. NEVER use these as Kotlin runtime results.
- External E5 bilingual retrieval on the 100-case set: **29/100 Top-1 standalone**, **41/100 with lexical**; **12/40** first-ranked on 40 newly authored non-opening queries. A separate 141M ONNX cross-encoder obtained **62/100 Top-1** but around **5.4s median** CPU candidate re-ranking in its Python experiment; it was not added to the Android app.
- Small LightGBM law rankers trained on the original 100 overfit badly (often 90%+ train vs 0–13% held-out Top-1); **never deploy based on training accuracy**.
- Experimental source-span ranker trained on 100 source-grounded cases selected an anchor-overlapping window in **21–23/40** held-out cases *when supplied the correct law*. This is not Android search accuracy, and remains unintegrated pending actual-engine A/B.
- Gold `quality_500_gold.json` is generated into `app/src/debug/assets/` with 500 original Unicode/UTF-16 source offsets. `MainActivity` has a `debug_quality_500` switch and output cohort counts for reviewed 140 versus synthetic 360, and `scripts/verify_android_100_accuracy.py` checks one-run strict Top-1 plus >=90% gold overlap and >=20% highlight precision; a passing source-derived suite must never be described as universal 100% understanding.

### Next work required
1. Collect ALL real Kotlin Q500 results from the Robolectric workflow, especially the **140 non-synthetic cases**, and failures grouped by Top-1 versus source-correct/answer-wrong. Preserve exact results, don't extrapolate success from candidate pool.
2. Improve cross-law evidence ranking and highlight selection generically using only source text and query semantics, without embedding benchmark law IDs or target answer phrases in production logic. Repeat **real Kotlin JVM** QA after changes, not only Python proxies.
3. Add multiple independently authored adversarial questions with non-first-law correct answers, ambiguous subjects, negations, delayed conditions, and explicit conflicting alternatives.
4. Measure actual on-device median/p95, crashes, memory and UI highlights after the user explicitly authorizes building an APK. Continue to honor the current no-APK constraint.

**Not completed**: 500/500 Top-1 and 500/500 truly precise turquoise answers in the real app; not a verified release.


## 12 November 2026 — verified source-engine upgrades and adversarial robustness

This section records **completed** GitHub Action measurements. Failures of
strict acceptance tests are expected until all measured Top-1 sources AND
answer spans pass. Do not label a quality-gate failure a build/compiler failure.

### Production source-only improvements (uncompiled into APK)
- Original Kotlin 500 question baseline: **431/500 Top-1**, **368/500**
  strict source-anchor and original-offset highlight, reviewed subset
  **71/140 Top-1** and **40/140 highlight**. 360/360 source-derived Top-1.
  [Run 43254018391](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43254018391).
- New `AdaptiveSpanRefiner.kt` in production CorpusStore, invoked for search
  top hit, deferred hits, and explicit law-open. It shrinks only when at least
  **70% of substantive question tokens literally occur** in the same authentic
  law, using original UTF-16 ranges, no answer-label memorization. Independent
  source-only A/B established zero net regression for reviewed cases and
  +13 passing source-derived highlights; the ACTUAL Kotlin engine confirmed
  **431/500 Top-1 and 381/500 answer-span passing**, reviewed **40/140**
  highlights, synthetic **341/360**. p50 around 150ms and p95 231ms in
  Robolectric, not a physical phone. [Run 43255416436](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43255416436).
- Tiny semantic snippet reranker experiment: best alternative improved only one
  manually authored question while keeping synthetics unchanged; NOT integrated.
  [Run 43255715270](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43255715270).

### Severe long-query failure found and fixed in source
- New `scripts/build_quality_560_wrapper_stress.py` generates **4 semantically
  neutral verbose context wrappers around each of 140 genuine authored
  questions**, i.e. **560 correlated test cases**, NOT 560 independent question
  labels. It validates exact corresponding authentic text/UTF-16 anchor offsets.
- First real Kotlin wrapper run: **150/560 Top-1**, **74/560 passing highlights**,
  **164/560 with zero results**, median 348ms, p95 506ms.
  [Run 43254688337](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43254688337).
- Generalized query decomposition in `SearchLanguage.evidenceSegments` now
  discards procedural instructions to find a source or omit a summary, but
  retains factual subquestions; literal user content remains available for
  independent candidate recall. `PreciseEvidenceSearch` retains raw-query
  char3 candidates and adds ranking from focused factual-clause char3 results.
- **New real Kotlin wrapper run:** **280/560 Top-1**, **156/560 passing
  highlights**, **8/560 with zero results**, median 153ms, p95 245ms.
  [Run 43256580234](https://github.com/AM246897531/virtual-garden-3d/actions/runs/43256580234).
  This increases first-source hits by **130 of 560** and reduces no-result
  queries by **156 of 560** in the same correlated stress suite.
- JVM Hebrew query regressions passed; further 560-case lexical preservation
  audit and joint 500+560 tests are configured in the Robolectric workflow.
  Tests running after the commits above are **not automatically proven to pass**;
  read their actual GitHub logs before promoting further claims.

### Methodological safeguards
- The 500 benchmark contains **140 reviewed / 360 source-derived** cases; the
  560 wrapper suite contains **140 parent questions x4**, not independent
  questions. Neither 500/500 candidate recall nor 560 source labels proves
  universal accuracy; strict Top-1 and full answer-span acceptance remain open.
- Only local source-grounded algorithms; no hardcoded gold law destinations in
  production. Kotlin source and Robolectric JVM tests, **NO APK** and **NO
  compilation/merge of production Android branch**.
- Research-only first-clause answer span ablation
  `RambamOpeningAnswerSpanAblationTest.kt` is pending a verified real Kotlin
  run. It must not be integrated if it harms the 140 manually worded cases.
- Remaining core gap: selecting the correct law among similar candidates for
  non-literal natural-language questions. Even after the notable wrapper fix,
  **280/560** Top-1 fails the user's **560/560** robustness criterion and the
  500 original test remained **431/500** Top-1 in the last verified run.

**User's task remains incomplete.** Continue from actual branch HEAD,
re-read recent GitHub workflow conclusions, and test each substantive
improvement on both original and independent-source QA.

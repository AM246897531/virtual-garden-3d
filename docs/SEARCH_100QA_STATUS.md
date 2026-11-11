# Mishneh Torah natural-language search — QA status (11 Nov 2026)

## Scope and safety
This is a **source-only development branch**. It is not a release and must not be merged as a verified 100/100 solution. The user instructed **DO NOT COMPILE AN APK YET**. The original APK workflow is restricted to another branch; the workflow here is explicitly Python-only.

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
- Debug QA now separately reports *goldAnchors* and *verifiedAnchors*. Mere in-bounds turquoise span is not considered proof of answer accuracy. Only **20 of the currently committed 100 reference cases have a gold answer anchor**.
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

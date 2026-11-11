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
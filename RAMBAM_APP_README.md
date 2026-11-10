# משנה תורה — אפליקציית Android מקומית

This feature branch contains a new native Android application separate from the repository's existing main branch.

## Build
The GitHub Actions workflow downloads a single licensed public-domain Hebrew edition of Mishneh Torah, validates all 14 sefarim, builds a local FTS4 SQLite database, and embeds it into a debug APK. No data or network permission is requested at runtime.

Local build prerequisites: Python 3.11, requests, Android SDK 35, JDK 17, Gradle 8.10.2.
Run:
1. `python -m pip install requests==2.32.3`
2. `python scripts/test_corpus_utils.py`
3. `python scripts/build_corpus.py`
4. `gradle :app:assembleDebug`

## Important implementation status
Native UI, offline corpus acquisition, lexical search, library browsing, reading and bookmarks are implemented in source.
**Neural semantic search is NOT yet implemented.** Do not describe this version as meeting the full requested model requirement.
**APK compilation, actual device launch, completeness, license metadata, and screenshots all require positive CI/runtime evidence.**
The debug APK is not a release-signed package.
The corpus builder fails rather than silently including incomplete or non-redistributable data.

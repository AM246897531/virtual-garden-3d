#!/usr/bin/env bash
set -euo pipefail
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="app.mishnehtorah"
OUT="artifacts/screenshots"
mkdir -p "$OUT"
# Disable the unstable emulator launcher before launching our standalone app.
# A Pixel Launcher ANR can obscure screenshots even when the target app works.
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb shell am force-stop com.android.launcher3 || true
adb shell pm disable-user --user 0 com.android.launcher3 || true
adb shell input keyevent 4 || true
adb install -r "$APK"
adb shell am start -n "$PKG/.MainActivity"
sleep 14
adb shell pidof "$PKG" > /dev/null || { adb logcat -d -t 500 > "$OUT/crash-logcat.txt"; exit 1; }
screenshot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  test -s "$OUT/$1.png"
  echo "CAPTURED $1"
}
start_query() {
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell "am start -n $PKG/.MainActivity --es debug_query '$1'"
  # Cold starts can take several seconds: do NOT fail on an immediate pidof
  # check before Android has created the app process. Instead wait for the
  # actual semantic+FTS completion marker logged from CorpusStore.search().
  local expected_section="${2:-}"
  for attempt in $(seq 1 30); do
    local match
    match="$(adb logcat -d -s RambamSearch:I '*:S' | grep 'RESULT count=[1-9]' | tail -n 1 || true)"
    if [[ -n "$match" ]]; then
      echo "SEARCH_RESULT_VERIFIED $1 : $match"
      if [[ -n "$expected_section" && "$match" != *"$expected_section"* ]]; then
        echo "FAILED: expected top search result to cite '$expected_section', got: $match" >&2
        adb logcat -d -t 1200 > "$OUT/quality-failure.txt"
        return 1
      fi
      sleep 1  # allow Compose to display the finished results before capture
      return 0
    fi
    sleep 2
  done
  adb logcat -d -t 1200 > "$OUT/search-failure.txt"
  echo "FAILED: search returned no records after 60 seconds: $1" >&2
  tail -n 140 "$OUT/search-failure.txt" >&2
  return 1
}
sleep 2
screenshot 01-home
S=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=$(echo "$S" | cut -dx -f1); H=$(echo "$S" | cut -dx -f2)
tap() { adb shell input tap "$1" "$2"; sleep 2; }
tap $((W/2)) $((H*20/100))
screenshot 02-search-focused
adb shell input keyevent 4 || true
start_query "שכחתי יעלה ויבוא בברכת המזון" "הלכות ברכות"
screenshot 03-birkat-hamazon-query
tap $((W/2)) $((H*43/100))
screenshot 04-opened-search-result
tap $((W*8/100)) $((H*7/100))
start_query "מה הדין אדם ששכח להזכיר ראש חודש" "הלכות ברכות"
screenshot 05-paraphrase-query
start_query "ר״ח ברכה חוזר" "הלכות ברכות"
screenshot 06-abbreviation-query
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity"
sleep 7
tap $((W*62/100)) $((H-95))
screenshot 07-library-books
tap $((W/2)) $((H*34/100))
screenshot 08-library-book
tap $((W/2)) $((H*34/100))
screenshot 09-library-section
tap $((W/2)) $((H*34/100))
screenshot 10-library-chapter
tap $((W/2)) $((H*42/100))
screenshot 11-reader-navigation
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity"
sleep 5
tap $((W*38/100)) $((H-95))
screenshot 12-bookmarks
tap $((W*22/100)) $((H-95))
screenshot 13-settings
tap $((W*20/100)) $((H*24/100))
screenshot 14-dark-mode
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null || true
start_query "ברכות"
screenshot 15-offline-search
adb shell settings put global airplane_mode_on 0
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null || true
adb logcat -d -t 900 > "$OUT/android-logcat.txt"
echo "EMULATOR_TEST_FINISHED"
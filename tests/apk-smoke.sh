#!/bin/sh
# Emulator check of the APK: install, seed an API key, tap a one-tap check, and
# watch the agent start. Usage: apk-smoke.sh phone-doctor.apk [deepseek-key] [out-dir]
# Without a real key the turn ends in an auth error, which still proves the
# runtime unpacked, prime-agent ran and its kernel came up.
set -u
APK=$1; KEY=${2:-sk-smoke-test-0000}; OUT=${3:-smoke-out}
PKG=dev.apkdbg
mkdir -p "$OUT"
adb install -r "$APK" || exit 1
adb logcat -c
# A debug build allows run-as, so seed the key the way the app stores it.
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/apkdbg.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="deepseek_key">$KEY</string></map>
XML
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell am start -W -n $PKG/.MainActivity || exit 1
sleep 8
adb exec-out screencap -p > "$OUT/1-start.png"

tap() { # tap the first node whose text contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml"
  B=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
want, xml = sys.argv[1], open(sys.argv[2], encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if want in (t.group(1) if t else "") or want in (d.group(1) if d else ""):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  [ -n "$B" ] || { echo "no node with text: $1"; return 1; }
  adb shell input tap $B
}

tap "Full health check" || { adb logcat -d -s apkdbg:* AndroidRuntime:E chromium:* > "$OUT/logcat.txt"; exit 1; }
# Wait for the turn to settle: an answer, an error row, or a failed agent.
i=0
while [ $i -lt 240 ]; do
  sleep 5; i=$((i+5))
  adb logcat -d -s apkdbg:* > "$OUT/logcat.txt"
  if grep -q 'event agent_end\|agent failed' "$OUT/logcat.txt"; then break; fi
  if ! adb shell pidof $PKG >/dev/null; then echo "app died"; break; fi
done
adb exec-out screencap -p > "$OUT/2-after.png"
adb logcat -d > "$OUT/logcat-full.txt"
adb shell "run-as $PKG ls files files/pyhome files/work" > "$OUT/files.txt" 2>&1
adb shell "run-as $PKG cat files/work/device_snapshot.json" > "$OUT/device_snapshot.json" 2>&1
echo "--- apkdbg log (tail)"; tail -n 80 "$OUT/logcat.txt"
echo "--- files"; cat "$OUT/files.txt"
echo "--- crashes"; grep -A 30 'FATAL EXCEPTION' "$OUT/logcat-full.txt" | head -60
grep -q 'FATAL EXCEPTION' "$OUT/logcat-full.txt" && { echo "crash"; exit 1; }
grep -q 'libpythonexe.so\|rlm' "$OUT/logcat-full.txt" || echo "warning: no sign of the kernel in the log"
grep -q 'agent failed' "$OUT/logcat.txt" && { echo "agent failed"; exit 1; }
echo "APK SMOKE DONE"

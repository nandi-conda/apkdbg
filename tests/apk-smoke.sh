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

find_node() { # print "x y" of the first node whose text or description contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml"
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
want, xml = sys.argv[1], open(sys.argv[2], encoding="utf-8", errors="replace").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if want in (t.group(1) if t else "") or want in (d.group(1) if d else ""):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}

tap() { # tap the first node whose text contains $1, waiting up to 30 s for it
  n=0
  while [ $n -lt 15 ]; do
    B=$(find_node "$1")
    if [ -n "$B" ]; then adb shell input tap $B; return 0; fi
    sleep 2; n=$((n+1))
  done
  echo "no node with text: $1. Texts on screen:"
  grep -o 'text="[^"]*"\|content-desc="[^"]*"' "$OUT/ui.xml" | grep -v '=""' | head -40
  adb exec-out screencap -p > "$OUT/no-node.png"
  return 1
}

if ! tap "Full health check"; then
  adb logcat -d > "$OUT/logcat-full.txt"
  echo "--- apkdbg / crash log"; grep -E ' apkdbg |AndroidRuntime|FATAL' "$OUT/logcat-full.txt" | tail -60
  adb shell "run-as $PKG ls -la shared_prefs; run-as $PKG cat shared_prefs/apkdbg.xml"
  exit 1
fi
# Wait for the turn to settle: an answer, an error row, or a failed agent.
i=0
while [ $i -lt 240 ]; do
  sleep 5; i=$((i+5))
  adb logcat -d -s apkdbg:* > "$OUT/logcat.txt"
  if grep -q 'event agent_end\|agent failed' "$OUT/logcat.txt"; then break; fi
  if ! adb shell pidof $PKG >/dev/null; then echo "app died"; break; fi
done
adb exec-out screencap -p > "$OUT/2-after.png"
find_node "zzz-none" >/dev/null
echo "--- texts on screen after the turn"
grep -o 'text="[^"]*"' "$OUT/ui.xml" | grep -v '=""' | head -40
# The keyboard must not cover the prompt box: tap it and compare its bounds with the IME's.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml"
B=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for n in re.findall(r'<node [^>]*>', open(sys.argv[1], encoding="utf-8", errors="replace").read()):
    if 'class="android.widget.EditText"' in n:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
if [ -z "$B" ]; then echo "keyboard check: no input box on screen"; exit 1; fi
adb shell input tap $B
sleep 3
adb exec-out screencap -p > "$OUT/3-keyboard.png"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui-keyboard.xml"
adb shell dumpsys window > "$OUT/dumpsys-window.txt"
python3 - "$OUT/ui-keyboard.xml" "$OUT/dumpsys-window.txt" <<'PY' || exit 1
import re, sys
ui = open(sys.argv[1], encoding="utf-8", errors="replace").read()
win = open(sys.argv[2], encoding="utf-8", errors="replace").read()
box = None
for n in re.findall(r'<node [^>]*>', ui):
    if 'class="android.widget.EditText"' in n:
        box = list(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())); break
ime = [list(map(int, m)) for m in re.findall(
    r'(?:m?[Tt]ype)=ime\s+m?[Ff]rame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]\s+m?[Vv]isible=true', win)]
ime = [f for f in ime if f[3] > f[1]]
print("keyboard check: input box", box, "visible IME frames", ime)
if box is None: sys.exit("keyboard check: the input box vanished after tapping it")
if not ime:
    print("warning: no visible keyboard reported; can't check that it leaves the input box clear"); sys.exit(0)
if box[3] > min(f[1] for f in ime) + 2: sys.exit("keyboard check: the keyboard covers the input box")
print("keyboard check: input box sits above the keyboard")
PY
adb shell input keyevent KEYCODE_BACK
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

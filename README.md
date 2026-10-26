# Phone doctor (apkdbg)

An Android app that runs [prime-agent](https://github.com/PrimeIntellect-ai/prime-agent) on the phone itself, on DeepSeek V4 Flash (`--provider deepseek --model deepseek-v4-flash`), to diagnose what is wrong with that phone. The UI is an [ultra](https://github.com/nandi-conda/ultra) app (LPython compiled to WebAssembly) with a chat, one-tap checks (battery, storage, slowness, network) and a field for your DeepSeek API key.

## Install

Download `phone-doctor.apk` from the [`apk-latest` release](https://github.com/nandi-conda/apkdbg/releases/tag/apk-latest), which CI rebuilds on every push to `main`. Open **Phone doctor**, go to **Settings**, and paste a DeepSeek API key (from platform.deepseek.com). The key stays in the app's private storage and is passed to prime-agent as `DEEPSEEK_API_KEY`.

An app can only see its own corner of Android. For deeper checks (system log, `dumpsys`, app usage) grant these once over USB debugging:

```sh
adb shell pm grant dev.apkdbg android.permission.READ_LOGS
adb shell pm grant dev.apkdbg android.permission.DUMP
adb shell pm grant dev.apkdbg android.permission.PACKAGE_USAGE_STATS
```

## How it works

- `web/` is the UI: `app.py` is the ultra app and `app.wasm` its committed build, with `index.html`, `style.css` and the `ultra.js` / `ui.css` runtime. In a desktop browser `index.html` fakes the agent so the page can be tried (`pixi run serve`).
- `android/` is a small Java shell (no dependencies). It serves `web/` to a WebView and answers `state.json` with the transcript. `Agent.java` runs `libprimeagent.so --mode rpc` with the bundled CPython as its kernel and folds the JSON-lines events into the transcript; `DeviceInfo.java` writes `device_snapshot.json` (battery, memory, storage, networks, thermal state...) into the agent's working directory first; `Prompt.java` tells the agent what it can and can't do in an app sandbox.
- `native/` and `.github/workflows/native.yml` build the native payload: prime-agent cross-compiled for Android, python.org's Android CPython 3.14 with an exec-able launcher, and a PYTHONHOME with prime-agent-runtime. It is published on the `native-latest` release, and `.github/workflows/apk.yml` packs it into the APK's `jniLibs` so it can be executed from `nativeLibraryDir`.

## Build

```sh
pixi run build      # web/app.py -> web/app.wasm
pixi run test       # browser test of the demo page (needs playwright on NODE_PATH)
cd android && ./gradlew assembleDebug   # JDK 17 + Android SDK; put the native payload in android/native-libs/<abi>/ first
```

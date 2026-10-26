# native payload

`.github/workflows/native.yml` builds `native-<abi>.tar.gz` (arm64-v8a, x86_64) on the `native-latest` release. Each holds `<abi>/`:

| file | what |
|---|---|
| `libprimeagent.so` | prime-agent CLI (Rust, `cargo ndk -P 26`), patched by `patches/prime-agent-android.patch` so the kernel only requires pure-python extras |
| `libpythonexe.so` | CPython launcher (`Py_BytesMain`), RUNPATH `$ORIGIN` |
| `libpython3.14.so`, `lib*_python.so` | python.org Android CPython 3.14 and its deps (OpenSSL, sqlite, libffi...) |
| `pythonhome.tar.gz` | `lib/python3.14/` stdlib + `site-packages` (prime-agent-runtime, tyro, requests, httpx, pyyaml, tomli, python-dotenv, beautifulsoup4, dill) |

Put the `.so` files in `jniLibs/<abi>/` (with `android:extractNativeLibs="true"` / `useLegacyPackaging = true` so they land as real files), extract `pythonhome.tar.gz` into app storage, and run `libprimeagent.so` with:

```
PRIME_AGENT_KERNEL_PYTHON=<nativeLibraryDir>/libpythonexe.so
PYTHONHOME=<extracted dir>
LD_LIBRARY_PATH=<nativeLibraryDir>
PRIME_AGENT_BASH_SHELL=/system/bin/sh
HOME=<filesDir>   TMPDIR=<cacheDir>
DEEPSEEK_API_KEY=<key>
libprimeagent.so --mode rpc --provider deepseek --model deepseek-v4-flash
```

`smoke.sh` is the on-device check the workflow runs on an x86_64 emulator.

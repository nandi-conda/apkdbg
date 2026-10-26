#!/system/bin/sh
# On-device smoke test. Usage: smoke.sh <dir holding the native/<abi> files>
set -u
D=$1
cd "$D" || exit 1
chmod 755 ./*.so
mkdir -p home pyhome
tar -xzf pythonhome.tar.gz -C pyhome || exit 1

export HOME="$D/home"
export TMPDIR="$D/home"
export LD_LIBRARY_PATH="$D"
export PYTHONHOME="$D/pyhome"
export PRIME_AGENT_KERNEL_PYTHON="$D/libpythonexe.so"
export PRIME_AGENT_BASH_SHELL=/system/bin/sh
export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-sk-dummy}"

echo "== python"
./libpythonexe.so -c 'import sys, ssl, sqlite3, ctypes; print(sys.version, sys.platform, ssl.OPENSSL_VERSION)' || exit 1
echo "== extras + runtime"
./libpythonexe.so -c 'import requests, httpx, yaml, tomli, dotenv, bs4, tyro, dill, rlm, rlm.repl, rlm.mcp; print("imports ok")' || exit 1

echo "== prime-agent --version"
./libprimeagent.so --version || exit 1

echo "== prime-agent rpc"
rm -f rpc.out rpc.err
( echo '{"id":"1","type":"get_state"}'
  sleep 2
  echo '{"id":"2","type":"prompt","message":"Run print(1+1) in python and reply with the result."}'
  sleep 60
  echo '{"id":"3","type":"get_state"}'
  sleep 2 ) | ./libprimeagent.so --mode rpc --provider deepseek --model deepseek-v4-flash >rpc.out 2>rpc.err &
PA=$!
i=0
KERNEL=0
while [ $i -lt 60 ]; do
  if ps -A -o ARGS 2>/dev/null | grep -v grep | grep -q 'libpythonexe.so -m rlm.repl'; then KERNEL=1; fi
  if ! kill -0 $PA 2>/dev/null; then break; fi
  sleep 1; i=$((i+1))
done
kill $PA 2>/dev/null
echo "--- rpc stdout (head)"; head -c 6000 rpc.out; echo
echo "--- rpc stderr (head)"; head -c 4000 rpc.err; echo
find home -name 'kernel-stderr.log' -exec sh -c 'echo "--- $1"; tail -n 40 "$1"' _ {} \;
echo "kernel_process_seen=$KERNEL"
grep -q '"id":"1"' rpc.out || { echo "no get_state response"; exit 1; }
[ $KERNEL = 1 ] || grep -qi 'kernel' rpc.out || { echo "kernel never started"; exit 1; }
echo "SMOKE OK"

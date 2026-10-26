"""Serve web/ on a free port and run tests/e2e.cjs against it."""
import functools
import http.server
import os
import socketserver
import subprocess
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=os.path.join(ROOT, "web"))
http.server.SimpleHTTPRequestHandler.extensions_map[".wasm"] = "application/wasm"
with socketserver.TCPServer(("127.0.0.1", 0), handler) as srv:
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = "http://127.0.0.1:%d/" % srv.server_address[1]
    sys.exit(subprocess.call(["node", os.path.join(ROOT, "tests", "e2e.cjs"), url] + sys.argv[1:]))

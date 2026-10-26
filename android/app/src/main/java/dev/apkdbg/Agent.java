package dev.apkdbg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs prime-agent in RPC mode (JSON lines on stdin/stdout) and keeps the
 * transcript the page shows. One per process; MainActivity and AgentService
 * share it through {@link #get}.
 */
final class Agent {
    static final String TAG = "apkdbg";
    static final String PROVIDER = "deepseek";
    static final String MODEL = "deepseek-v4-flash";

    // Status codes, as web/app.py reads them from state.json.
    static final int NO_KEY = 0, IDLE = 1, STARTING = 2, RUNNING = 3, FAILED = 4, NO_RUNTIME = 5;
    // Transcript roles.
    static final int USER = 0, ASSISTANT = 1, TOOL = 2, ERROR = 3, INFO = 4;

    private static final int MAX_ITEMS = 256;
    private static final int MAX_TOOL_TEXT = 2000;

    private static Agent instance;

    static synchronized Agent get(Context c) {
        if (instance == null) instance = new Agent(c.getApplicationContext());
        return instance;
    }

    interface Listener { void changed(); }

    private static final class Item {
        final int role;
        final StringBuilder text = new StringBuilder();
        Item(int role, String text) { this.role = role; this.text.append(text); }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private int rev = 0;
    private int status;
    private String detail = "";
    private Process proc;
    private OutputStream stdin;
    private int generation = 0;       // bumps on every (re)start, so old reader threads go quiet
    private final StringBuilder stderrTail = new StringBuilder();
    private Item streaming;           // the assistant row the current message's text goes into
    private String pendingPrompt;     // sent once the agent is up

    private Agent(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("apkdbg", Context.MODE_PRIVATE);
        status = key().isEmpty() ? NO_KEY : IDLE;
        if (!Runtime.bundled(c)) {
            status = NO_RUNTIME;
            detail = "This build doesn't include the agent runtime (libprimeagent.so). Install an APK from the apk-latest release.";
        }
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        rev++;
        for (Listener l : listeners) l.changed();
    }

    // ---------------------------------------------------------------- key

    String key() { return prefs.getString("deepseek_key", ""); }

    synchronized void saveKey(String k) {
        prefs.edit().putString("deepseek_key", k.trim()).apply();
        stopProcess();
        if (status != NO_RUNTIME) { status = IDLE; detail = ""; }
        changed();
    }

    synchronized void clearKey() {
        prefs.edit().remove("deepseek_key").apply();
        stopProcess();
        if (status != NO_RUNTIME) status = NO_KEY;
        changed();
    }

    private String keyHint() {
        String k = key();
        if (k.isEmpty()) return "";
        if (k.length() <= 8) return "…" + k.substring(Math.max(0, k.length() - 2));
        return k.substring(0, 3) + "…" + k.substring(k.length() - 4);
    }

    // ---------------------------------------------------------------- state.json

    synchronized String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("rev", rev);
            o.put("status", status);
            o.put("detail", detail);
            o.put("hasKey", key().isEmpty() ? 0 : 1);
            o.put("keyHint", keyHint());
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject r = new JSONObject();
                r.put("r", it.role);
                r.put("t", it.text.toString());
                a.put(r);
            }
            o.put("items", a);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- commands from the page

    synchronized void prompt(String text) {
        if (text == null || text.trim().isEmpty() || status == RUNNING || status == NO_RUNTIME) return;
        if (key().isEmpty()) { status = NO_KEY; changed(); return; }
        add(USER, text.trim());
        status = RUNNING;
        detail = "Starting the agent…";
        AgentService.start(ctx);
        if (proc == null || !proc.isAlive()) {
            pendingPrompt = text.trim();
            startProcess();
        } else {
            send(text.trim());
        }
        changed();
    }

    synchronized void abort() {
        if (status != RUNNING) return;
        pendingPrompt = null;
        write(command("abort"));
        status = IDLE;
        detail = "";
        streaming = null;
        add(INFO, "Stopped.");
        AgentService.stop(ctx);
        changed();
    }

    synchronized void newChat() {
        stopProcess();
        items.clear();
        streaming = null;
        if (status != NO_RUNTIME) {
            status = key().isEmpty() ? NO_KEY : IDLE;
            detail = "";
        }
        changed();
    }

    // ---------------------------------------------------------------- the process

    private void startProcess() {
        stopProcess();
        final int gen = ++generation;
        stderrTail.setLength(0);
        status = RUNNING;
        detail = "Starting the agent…";
        new Thread(() -> launch(gen), "agent-launch").start();
    }

    private void launch(int gen) {
        try {
            File pyhome = Runtime.ensurePythonHome(ctx, (msg) -> progress(gen, msg));
            File work = new File(ctx.getFilesDir(), "work");
            File home = new File(ctx.getFilesDir(), "home");
            work.mkdirs();
            home.mkdirs();
            progress(gen, "Reading device state…");
            DeviceInfo.writeSnapshot(ctx, new File(work, "device_snapshot.json"));

            String lib = ctx.getApplicationInfo().nativeLibraryDir;
            ProcessBuilder pb = new ProcessBuilder(
                    lib + "/libprimeagent.so",
                    "--mode", "rpc",
                    "--provider", PROVIDER,
                    "--model", MODEL,
                    "--append-system-prompt", Prompt.text(ctx, work));
            pb.directory(work);
            Map<String, String> env = pb.environment();
            env.put("DEEPSEEK_API_KEY", key());
            env.put("PRIME_AGENT_KERNEL_PYTHON", lib + "/libpythonexe.so");
            env.put("PYTHONHOME", pyhome.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", lib);
            env.put("PRIME_AGENT_BASH_SHELL", "/system/bin/sh");
            env.put("SHELL", "/system/bin/sh");
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            env.put("PATH", lib + ":/system/bin:/system/xbin:/vendor/bin");
            env.put("TERM", "dumb");
            env.put("NO_COLOR", "1");
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            progress(gen, "Starting prime-agent…");
            Process p = pb.start();
            synchronized (this) {
                if (gen != generation) { p.destroy(); return; }
                proc = p;
                stdin = p.getOutputStream();
            }
            new Thread(() -> readStdout(gen, p.getInputStream()), "agent-stdout").start();
            new Thread(() -> readStderr(gen, p.getErrorStream()), "agent-stderr").start();
            new Thread(() -> waitExit(gen, p), "agent-wait").start();
            synchronized (this) {
                detail = "Thinking…";
                if (pendingPrompt != null) {
                    send(pendingPrompt);
                    pendingPrompt = null;
                }
                changed();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "launch", e);
            synchronized (this) {
                if (gen != generation) return;
                fail("Couldn't start the agent: " + e.getMessage());
            }
        }
    }

    private synchronized void progress(int gen, String msg) {
        if (gen != generation) return;
        detail = msg;
        changed();
    }

    private void stopProcess() {
        generation++;
        pendingPrompt = null;
        if (proc != null) {
            proc.destroy();
            Process p = proc;
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                if (p.isAlive()) p.destroyForcibly();
            }).start();
        }
        proc = null;
        stdin = null;
        streaming = null;
        AgentService.stop(ctx);
    }

    private void send(String message) {
        try {
            JSONObject o = command("prompt");
            o.put("message", message);
            write(o);
        } catch (JSONException ignored) { }
        detail = "Thinking…";
    }

    private static JSONObject command(String type) {
        JSONObject o = new JSONObject();
        try { o.put("type", type); } catch (JSONException ignored) { }
        return o;
    }

    private void write(JSONObject o) {
        if (stdin == null) return;
        try {
            stdin.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            Log.w(TAG, "write", e);
        }
    }

    private void readStdout(int gen, InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONObject ev;
                try {
                    ev = new JSONObject(line);
                } catch (JSONException e) {
                    Log.d(TAG, "stdout: " + line);
                    continue;
                }
                synchronized (this) {
                    if (gen != generation) return;
                    if (handle(ev)) changed();
                }
            }
        } catch (IOException ignored) { }
    }

    private void readStderr(int gen, InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Log.d(TAG, "stderr: " + line);
                synchronized (this) {
                    if (gen != generation) return;
                    stderrTail.append(line).append('\n');
                    if (stderrTail.length() > 4000) stderrTail.delete(0, stderrTail.length() - 4000);
                    // Kernel bootstrap progress lines are worth showing while starting.
                    if (status == RUNNING && streaming == null && line.length() < 160 && !line.trim().isEmpty()) {
                        detail = line.trim();
                        changed();
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    private void waitExit(int gen, Process p) {
        int code;
        try { code = p.waitFor(); } catch (InterruptedException e) { return; }
        synchronized (this) {
            if (gen != generation) return;
            proc = null;
            stdin = null;
            String tail = stderrTail.toString().trim();
            fail("prime-agent exited (code " + code + ")" + (tail.isEmpty() ? "." : ":\n" + tail));
        }
    }

    private void fail(String why) {
        Log.w(TAG, "agent failed: " + why);
        status = FAILED;
        detail = why;
        streaming = null;
        AgentService.stop(ctx);
        changed();
    }

    /** Fold one RPC event into the transcript. Returns whether anything visible changed. */
    private boolean handle(JSONObject ev) {
        String type = ev.optString("type");
        if (!"message_update".equals(type)) Log.d(TAG, "event " + type + (type.equals("response") ? " " + ev : ""));
        switch (type) {
            case "response": {
                if (!ev.optBoolean("success", true)) {
                    add(ERROR, ev.optString("command") + " failed: " + ev.optString("error"));
                    if ("prompt".equals(ev.optString("command"))) {
                        status = IDLE;
                        detail = "";
                        AgentService.stop(ctx);
                    }
                    return true;
                }
                return false;
            }
            case "agent_start":
                status = RUNNING;
                detail = "Thinking…";
                return true;
            case "message_start": {
                JSONObject m = ev.optJSONObject("message");
                if (m != null && "assistant".equals(m.optString("role"))) streaming = null;
                return false;
            }
            case "message_update": {
                JSONObject d = ev.optJSONObject("assistantMessageEvent");
                if (d == null) return false;
                String dt = d.optString("type");
                if ("text_delta".equals(dt)) {
                    if (streaming == null) streaming = add(ASSISTANT, "");
                    streaming.text.append(d.optString("delta"));
                    detail = "Writing…";
                    return true;
                } else if ("thinking_start".equals(dt)) {
                    detail = "Thinking…";
                    return true;
                } else if ("toolcall_start".equals(dt)) {
                    detail = "Preparing a step…";
                    return true;
                }
                return false;
            }
            case "message_end": {
                JSONObject m = ev.optJSONObject("message");
                if (m != null && "assistant".equals(m.optString("role"))) {
                    String err = m.optString("errorMessage", "");
                    if (!err.isEmpty()) add(ERROR, err);
                }
                streaming = null;
                return true;
            }
            case "tool_execution_start": {
                streaming = null;
                add(TOOL, toolSummary(ev.optString("toolName"), ev.opt("args")));
                detail = "Running " + ev.optString("toolName") + "…";
                return true;
            }
            case "tool_execution_end": {
                String out = resultText(ev.opt("result"));
                Item last = lastTool();
                if (last != null && !out.isEmpty()) {
                    last.text.append("\n→ ").append(clip(out));
                }
                if (ev.optBoolean("isError") && last != null) last.text.append("\n(failed)");
                detail = "Thinking…";
                return true;
            }
            case "agent_end":
                status = IDLE;
                detail = "";
                streaming = null;
                AgentService.stop(ctx);
                return true;
            default:
                return false;
        }
    }

    private Item add(int role, String text) {
        Item it = new Item(role, text);
        items.add(it);
        while (items.size() > MAX_ITEMS) items.remove(0);
        return it;
    }

    private Item lastTool() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).role == TOOL) return items.get(i);
            if (items.get(i).role == USER) break;
        }
        return null;
    }

    private static String toolSummary(String name, Object args) {
        String body = "";
        if (args instanceof JSONObject) {
            JSONObject a = (JSONObject) args;
            for (String k : new String[] {"code", "command", "cmd", "source", "path", "query"}) {
                if (a.has(k)) { body = a.optString(k); break; }
            }
            if (body.isEmpty()) body = a.toString();
        } else if (args != null) {
            body = String.valueOf(args);
        }
        return name + ": " + clip(body);
    }

    private static String resultText(Object result) {
        if (!(result instanceof JSONObject)) return result == null ? "" : String.valueOf(result);
        JSONArray content = ((JSONObject) result).optJSONArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c != null && "text".equals(c.optString("type"))) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.optString("text"));
            }
        }
        return sb.toString().trim();
    }

    private static String clip(String s) {
        s = s.trim();
        return s.length() <= MAX_TOOL_TEXT ? s : s.substring(0, MAX_TOOL_TEXT) + "\n… (" + (s.length() - MAX_TOOL_TEXT) + " more characters)";
    }
}

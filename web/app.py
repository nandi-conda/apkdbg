from lpython import i32, Const
from numpy import empty, int32
from ultra import (el, end, text, text_ref, num, cls, cls_if, attr, key, autofocus,
                   on_enter, div, s_new, s_set, s_copy, s_clear, s_len,
                   http_get, j_ok, j_err, j_at, j_idx, j_len, j_int, j_str, j_free)
from ui import (btn, btn_open, input_open, label, badge, alert, tabs_list_open, tab,
                card_open, card_header, card_content_open, card_footer_open)

# apkdbg: a chat with prime-agent (DeepSeek V4 Flash) that diagnoses the phone
# it runs on. The native shell (android/) runs the agent and keeps the
# transcript; this page shows it and sends prompts. Whenever the transcript
# changes the shell calls window.apkdbg.changed(), which dispatches REFRESH,
# and the page fetches state.json. In a desktop browser index.html stands in.

@jscall
def send_prompt(h: i32) -> None:
    """Send the text of string handle h to the agent as the next user turn."""
    pass

@jscall
def save_key(h: i32) -> None:
    """Store the DeepSeek API key in string handle h (the shell keeps it, not the page)."""
    pass

@jscall
def clear_key() -> None:
    pass

@jscall
def stop_agent() -> None:
    """Abort the turn in progress."""
    pass

@jscall
def new_chat() -> None:
    """Restart the agent with an empty session."""
    pass

@jscall
def open_url(h: i32) -> None:
    pass

# ---- Msg (index.html sends REFRESH; keep in sync)
REFRESH: Const[i32] = 1
LOADED: Const[i32] = 2
PROMPT_INPUT: Const[i32] = 3
SEND: Const[i32] = 4
KEY_INPUT: Const[i32] = 5
SAVE_KEY: Const[i32] = 6
CLEAR_KEY: Const[i32] = 7
STOP: Const[i32] = 8
NEW_CHAT: Const[i32] = 9
TAB: Const[i32] = 10
QUICK: Const[i32] = 11       # arg: which preset diagnosis
TOGGLE_SHOW: Const[i32] = 12
GET_KEY: Const[i32] = 13

# Agent status, as the shell reports it in state.json
NO_KEY: Const[i32] = 0
IDLE: Const[i32] = 1
STARTING: Const[i32] = 2
RUNNING: Const[i32] = 3
FAILED: Const[i32] = 4
NO_RUNTIME: Const[i32] = 5

# Transcript roles
USER: Const[i32] = 0
ASSISTANT: Const[i32] = 1
TOOL: Const[i32] = 2
ERROR: Const[i32] = 3
INFO: Const[i32] = 4

CHAT: Const[i32] = 0
SETTINGS: Const[i32] = 1

CAP: Const[i32] = 256

# ---- Model
# Transcript rows as parallel columns: role and a string handle with the text.
roles: i32[256] = empty(256, dtype=int32)
texts: i32[256] = empty(256, dtype=int32)
n: i32 = 0
allocated: i32 = 0     # texts[0:allocated] hold live handles, reused across refreshes
rev: i32 = -1
status: i32 = STARTING
detail: i32 = 0        # status detail (an error message, the runtime's progress)
has_key: i32 = 0
key_hint: i32 = 0      # "sk-…1234"
draft: i32 = 0
key_draft: i32 = 0
show_key: i32 = 0
page: i32 = CHAT
loading: i32 = 0
again: i32 = 0         # a REFRESH came in while state.json was loading
quick_text: i32 = 0
url: i32 = 0
pending: i32 = 0


def init() -> None:
    global draft, key_draft, detail, key_hint, quick_text, url
    draft = s_new()
    key_draft = s_new()
    detail = s_new()
    key_hint = s_new()
    quick_text = s_new()
    url = s_new()
    refresh()


def refresh() -> None:
    global loading, again, pending
    if loading == 1:
        again = 1
        return
    loading = 1
    pending = http_get("state.json", LOADED)


def load(doc: i32) -> None:
    global n, allocated, rev, status, has_key
    i: i32
    count: i32
    items: i32
    item: i32
    ok: i32
    if j_ok(doc) == 0:
        status = FAILED
        j_err(doc, detail)
        return
    rev = j_int(doc, "rev")
    status = j_int(doc, "status")
    has_key = j_int(doc, "hasKey")
    ok = j_str(doc, "detail", detail)
    ok = j_str(doc, "keyHint", key_hint)
    count = j_len(doc, "items")
    if count > CAP:
        count = CAP
    while allocated < count:
        texts[allocated] = s_new()
        allocated += 1
    items = j_at(doc, "items")
    for i in range(count):
        item = j_idx(items, i)
        roles[i] = j_int(item, "r")
        ok = j_str(item, "t", texts[i])
        j_free(item)
    j_free(items)
    n = count


def update(msg: i32, arg: i32) -> None:
    global loading, again, page, show_key, has_key
    if msg == REFRESH:
        refresh()
    elif msg == LOADED:
        loading = 0
        load(arg)
        j_free(arg)
        if again == 1:
            again = 0
            refresh()
    elif msg == PROMPT_INPUT:
        s_copy(draft, arg)
    elif msg == SEND:
        if s_len(draft) > 0 and has_key == 1 and status != RUNNING:
            send_prompt(draft)
            s_clear(draft)
    elif msg == KEY_INPUT:
        s_copy(key_draft, arg)
    elif msg == SAVE_KEY:
        if s_len(key_draft) > 0:
            save_key(key_draft)
            s_clear(key_draft)
            show_key = 0
            page = CHAT
    elif msg == CLEAR_KEY:
        clear_key()
        page = SETTINGS
    elif msg == STOP:
        stop_agent()
    elif msg == NEW_CHAT:
        new_chat()
    elif msg == TAB:
        page = arg
    elif msg == TOGGLE_SHOW:
        show_key = 1 - show_key
    elif msg == GET_KEY:
        s_set(url, "https://platform.deepseek.com/api_keys")
        open_url(url)
    elif msg == QUICK:
        quick(arg)


def quick(which: i32) -> None:
    if has_key == 0 or status == RUNNING:
        return
    if which == 0:
        s_set(quick_text, "Run a general health check of this phone: battery, storage, memory, thermal state, network and uptime. List anything that looks wrong, most serious first, with what I should do about it.")
    elif which == 1:
        s_set(quick_text, "My battery drains fast. Find out what could be using it (battery health and temperature, charging state, screen, wakeful processes, recent uptime) and tell me what to change.")
    elif which == 2:
        s_set(quick_text, "My storage is nearly full. Measure what is using space that this app can see and tell me where the space is going and how to free it.")
    elif which == 3:
        s_set(quick_text, "The phone feels slow. Check memory pressure, CPU load, thermal throttling and running processes, and tell me what is likely causing it.")
    elif which == 4:
        s_set(quick_text, "I have network problems. Check connectivity, DNS, which networks are up, and latency to a few well-known hosts, and tell me where it breaks.")
    send_prompt(quick_text)

# ---- View

def status_badge() -> None:
    if status == RUNNING:
        badge("Working", "")
    elif status == STARTING:
        badge("Starting", "secondary")
    elif status == IDLE:
        badge("Ready", "outline")
    elif status == NO_KEY:
        badge("No API key", "destructive")
    elif status == NO_RUNTIME:
        badge("No runtime", "destructive")
    else:
        badge("Error", "destructive")


def header() -> None:
    el("header")
    cls("top")
    div("brand")
    el("span")
    cls("logo")
    text("dbg")
    end()
    div("titles")
    el("h1")
    text("Phone doctor")
    end()
    el("p")
    cls("muted")
    text("prime-agent · DeepSeek V4 Flash")
    end()
    end()
    end()
    status_badge()
    end()
    tabs_list_open()
    tab("Chat", page == CHAT, TAB, CHAT)
    tab("Settings", page == SETTINGS, TAB, SETTINGS)
    end()


def view_row(i: i32) -> None:
    el("li")
    key(i + 1)
    cls("msg")
    cls_if("user", roles[i] == USER)
    cls_if("assistant", roles[i] == ASSISTANT)
    cls_if("tool", roles[i] == TOOL)
    cls_if("error", roles[i] == ERROR)
    cls_if("info", roles[i] == INFO)
    if roles[i] == TOOL:
        el("div")
        cls("tool-label")
        text("ran")
        end()
    el("div")
    cls("body")
    text_ref(texts[i])
    end()
    end()


def view_empty() -> None:
    div("empty")
    el("p")
    cls("lead")
    text("Describe what is wrong with your phone, or start with a check:")
    end()
    div("quick")
    btn("Full health check", "outline", QUICK, 0)
    btn("Battery drains fast", "outline", QUICK, 1)
    btn("Storage is full", "outline", QUICK, 2)
    btn("Phone is slow", "outline", QUICK, 3)
    btn("Network problems", "outline", QUICK, 4)
    end()
    end()


def view_composer() -> None:
    el("footer")
    cls("composer")
    input_open("Ask about your phone…", draft, PROMPT_INPUT)
    on_enter(SEND, 0)
    attr("enterkeyhint", "send")
    end()
    if status == RUNNING:
        btn("Stop", "destructive", STOP, 0)
    else:
        btn_open("", SEND, 0)
        if s_len(draft) == 0 or has_key == 0:
            attr("disabled", "")
        text("Send")
        end()
    end()


def view_chat() -> None:
    i: i32
    if has_key == 0:
        div("pad")
        alert("Add your DeepSeek API key", "The agent needs a DeepSeek key to run. Open Settings to paste one.", "")
        btn("Open settings", "", TAB, SETTINGS)
        end()
        return
    if status == FAILED or status == NO_RUNTIME:
        div("pad")
        el("div")
        cls("alert")
        cls("destructive")
        attr("role", "alert")
        el("div")
        cls("alert-title")
        text("The agent stopped")
        end()
        el("div")
        cls("alert-description")
        text_ref(detail)
        end()
        end()
        btn("Restart agent", "outline", NEW_CHAT, 0)
        end()
    if n == 0:
        view_empty()
    else:
        el("ul")
        cls("transcript")
        for i in range(n):
            view_row(i)
        end()
        if status == RUNNING:
            div("thinking")
            el("span")
            cls("dot")
            end()
            text_ref(detail)
            end()
        div("chat-actions")
        btn("New chat", "ghost", NEW_CHAT, 0)
        end()
    view_composer()


def view_settings() -> None:
    div("pad")
    card_open()
    card_header("DeepSeek API key", "Used for every request the agent makes. It is stored on this phone only and sent only to api.deepseek.com.")
    card_content_open()
    if has_key == 1:
        div("saved")
        label("Saved key")
        el("code")
        text_ref(key_hint)
        end()
        end()
    label("New key")
    div("key-row")
    input_open("sk-…", key_draft, KEY_INPUT)
    if show_key == 0:
        attr("type", "password")
    else:
        attr("type", "text")
    attr("autocomplete", "off")
    attr("spellcheck", "false")
    on_enter(SAVE_KEY, 0)
    end()
    if show_key == 1:
        btn("Hide", "outline", TOGGLE_SHOW, 0)
    else:
        btn("Show", "outline", TOGGLE_SHOW, 0)
    end()
    end()
    card_footer_open()
    btn_open("", SAVE_KEY, 0)
    if s_len(key_draft) == 0:
        attr("disabled", "")
    text("Save key")
    end()
    if has_key == 1:
        btn("Remove key", "ghost", CLEAR_KEY, 0)
    btn("Get a key", "link", GET_KEY, 0)
    end()
    end()

    card_open()
    card_header("Model", "")
    card_content_open()
    el("p")
    text("prime-agent runs on ")
    el("code")
    text("deepseek-v4-flash")
    end()
    text(" through the DeepSeek API.")
    end()
    end()
    end()

    card_open()
    card_header("Deeper diagnostics (optional)", "")
    card_content_open()
    el("p")
    text("Android only lets an app read its own logs and system state. To let the agent read the system log and dumpsys, connect the phone to a computer with USB debugging on and run:")
    end()
    el("pre")
    text("adb shell pm grant dev.apkdbg android.permission.READ_LOGS\nadb shell pm grant dev.apkdbg android.permission.DUMP\nadb shell pm grant dev.apkdbg android.permission.PACKAGE_USAGE_STATS")
    end()
    el("p")
    cls("muted")
    text("Then start a new chat. Nothing else changes, and you can revoke them the same way with pm revoke.")
    end()
    end()
    end()
    end()


def view() -> None:
    div("shell")
    cls_if("busy", status == RUNNING)
    header()
    if page == SETTINGS:
        view_settings()
    else:
        view_chat()
    end()

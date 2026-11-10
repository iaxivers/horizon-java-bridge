#!/usr/bin/env python3
"""Bridge vs real Fabric: the same mods, seed and scripted scenario on two dedicated servers.

  python3 tools/java_bridge/bench_vs_fabric.py --mods ~/hz-test/mods-java

Side "fabric": a real Fabric 26.3 dedicated server. Fabric's installer is downloaded at run time
from maven.fabricmc.net into a scratch folder (with the loader's libraries); vanilla's
server.jar comes from .local (the player's own prepared files). Nothing of it is committed.
Side "horizon": `horizon-client --dedicated --nogui` with the same jars in `mods-java/` (the
bridge, built with `java-mods-dev`, as server_test.sh does).

Both get the same server.properties (offline, seed) and the same console commands (a FIFO).
The test area is force-loaded (/forceload); new chunks are loaded by a horizon-bots bot exploring
(`--fabric`: it answers Fabric API's registry sync, which refuses plain clients once mods add blocks).

 1. Start: wall time from launch to ready: "Done (" (Horizon: and its Java side's start hooks,
    which a warm start runs after "Done").
 2. Build the test area in the air (y 200, chunks force-loaded, frozen with /tick freeze):
    item lines of vanilla hoppers (chest -> L hoppers -> chest), of Create chutes
    (chest -> hopper -> chutes -> chest) and Create kinetics (creative motor -> shafts).
 3. Parity: `/tick step K` from the same frozen state, then every container's items and every
    kinetic block's speed (`/data get block`), compared between the sides; dumped again 5 s
    later, still frozen: anything that moved ticks through `/tick freeze`.
 4. Load: unfrozen for --run-seconds, `/tick query` every 5 s (each answer covers the last
    100 ticks), RSS every 5 s; then a bot flies out over new chunks for --explore-seconds.
 5. Save: frozen, the area dumped, `save-all flush`, `stop`, restarted on the same folder, the
    area dumped again (frozen as soon as the console answers): every block entity must be back
    with its id, and each line must hold the same items in total (items only move within a
    line, so a tick or two before the freeze can't change a line's total).
 6. Cross-open (--cross-open): Horizon's saved world opened by the real Fabric server, the
    same check (a player going back to Java must find their machines and items).

Writes OUT/report.md (a markdown table) and OUT/report.json, the servers' logs and dumps.
Uses only scratch folders (CLAUDE.md): HORIZON_HOME, XDG_* and the launcher's folders.
"""

import argparse
import collections
import json
import os
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request

R = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
META = "https://meta.fabricmc.net/v2/versions"
AREA_Y = 200


# ---------------------------------------------------------------- SNBT (what /data get prints)


def parse_snbt(text):
    """A small SNBT reader: compounds, lists, typed arrays, strings, numbers (suffixes dropped)."""
    i = 0
    n = len(text)

    def ws():
        nonlocal i
        while i < n and text[i] in " \t\r\n":
            i += 1

    def value():
        nonlocal i
        ws()
        c = text[i]
        if c == "{":
            i += 1
            out = {}
            ws()
            if text[i] == "}":
                i += 1
                return out
            while True:
                ws()
                k = string() if text[i] in "\"'" else bare(key=True)
                ws()
                assert text[i] == ":", text[i - 20 : i + 20]
                i += 1
                out[k] = value()
                ws()
                if text[i] == ",":
                    i += 1
                    continue
                assert text[i] == "}", text[i - 20 : i + 20]
                i += 1
                return out
        if c == "[":
            i += 1
            ws()
            if text[i : i + 2] in ("B;", "I;", "L;"):
                i += 2
            out = []
            ws()
            if text[i] == "]":
                i += 1
                return out
            while True:
                out.append(value())
                ws()
                if text[i] == ",":
                    i += 1
                    continue
                assert text[i] == "]", text[i - 20 : i + 20]
                i += 1
                return out
        if c in "\"'":
            return string()
        word = bare()
        m = re.fullmatch(r"([-+]?[0-9.eE+-]+)[bBsSlLfFdD]?", word)
        if m:
            num = m.group(1)
            try:
                return int(num)
            except ValueError:
                return float(num)
        return {"true": 1, "false": 0}.get(word, word)

    def string():
        nonlocal i
        q = text[i]
        i += 1
        out = []
        while text[i] != q:
            if text[i] == "\\":
                i += 1
            out.append(text[i])
            i += 1
        i += 1
        return "".join(out)

    def bare(key=False):
        nonlocal i
        s = i
        while i < n and (text[i].isalnum() or text[i] in ("_-.+" if key else "_-.+:")):
            i += 1
        return text[s:i]

    return value()


def items_in(nbt):
    """Every item stack anywhere in a block entity's NBT: {item id: count}."""
    out = collections.Counter()

    def walk(v):
        if isinstance(v, dict):
            if isinstance(v.get("id"), str) and isinstance(v.get("count"), int):
                out[v["id"]] += v["count"]
                return
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)

    walk({k: v for k, v in nbt.items() if k not in ("id", "x", "y", "z")})
    return dict(out)


# ---------------------------------------------------------------- the scenario


def scenario(a):
    """The test area: blocks to place in order, containers to dump, and each block's line."""
    x0, z0, y = 0, 0, AREA_Y
    cmds, dump, line_of = [], [], {}
    width = max(a.hopper_len + 2, 12)
    depth = 2 * (a.lines + a.chute_lines + a.kinetic_lines) + 2
    box = (x0 - 1, y - 1 - a.chute_len - 2, z0 - 1, x0 + width + 1, y + 2, z0 + depth + 1)
    for zs in range(box[2], box[5] + 1, 32):  # /fill takes at most 32768 blocks
        cmds.append("fill %d %d %d %d %d %d minecraft:air" % (box[0], box[1], zs, box[3], box[4], min(zs + 31, box[5])))
    stock = lambda kinds: "{Items:[%s]}" % ",".join(
        '{Slot:%db,id:"minecraft:%s",count:64}' % (s, kinds[s % len(kinds)]) for s in range(27)
    )
    z = z0
    for i in range(a.lines):  # chest -> hoppers east -> chest
        name = "hopper%d" % i
        src, sink = (x0, y + 1, z), (x0 + a.hopper_len, y, z)
        cmds.append("setblock %d %d %d minecraft:chest%s" % (*src, stock(["cobblestone", "oak_log", "iron_ingot"])))
        for k in range(a.hopper_len):
            p = (x0 + k, y, z)
            cmds.append("setblock %d %d %d minecraft:hopper[facing=east]" % p)
            dump.append(p)
            line_of[p] = name
        cmds.append("setblock %d %d %d minecraft:chest" % sink)
        for p in (src, sink):
            dump.append(p)
            line_of[p] = name
        z += 2
    for i in range(a.chute_lines):  # chest -> hopper down -> Create chutes -> chest
        name = "chute%d" % i
        src, hop = (x0, y + 1, z), (x0, y, z)
        cmds.append("setblock %d %d %d minecraft:chest%s" % (*src, stock(["andesite", "copper_ingot"])))
        cmds.append("setblock %d %d %d minecraft:hopper[facing=down]" % hop)
        ps = [src, hop]
        for k in range(a.chute_len):
            p = (x0, y - 1 - k, z)
            cmds.append("setblock %d %d %d create:chute" % p)
            ps.append(p)
        sink = (x0, y - 1 - a.chute_len, z)
        cmds.append("setblock %d %d %d minecraft:chest" % sink)
        ps.append(sink)
        for p in ps:
            dump.append(p)
            line_of[p] = name
        z += 2
    for i in range(a.kinetic_lines):  # creative motor -> shafts east
        name = "kinetic%d" % i
        p = (x0, y, z)
        cmds.append("setblock %d %d %d create:creative_motor[facing=east]" % p)
        ps = [p]
        for k in range(1, 9):
            q = (x0 + k, y, z)
            cmds.append("setblock %d %d %d create:shaft[axis=x]" % q)
            ps.append(q)
        for q in ps:
            dump.append(q)
            line_of[q] = name
        z += 2
    chunks = (box[0], box[2], box[3], box[5])
    return cmds, dump, line_of, chunks


# ---------------------------------------------------------------- servers


def rss_kb(pid, key="VmRSS"):
    try:
        for line in open("/proc/%d/status" % pid):
            if line.startswith(key + ":"):
                return int(line.split()[1])
    except OSError:
        pass
    return 0


def heap(pid, jcmd):
    """(used MB, committed MB) of the JVM in pid, from jcmd GC.heap_info."""
    try:
        out = subprocess.run([jcmd, str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=60).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"total reserved (\d+)K, committed (\d+)K, used (\d+)K", out)
    if not m:
        m2 = re.search(r"total (\d+)K, used (\d+)K", out)
        return (int(m2.group(2)) / 1024, int(m2.group(1)) / 1024) if m2 else None
    return int(m.group(3)) / 1024, int(m.group(2)) / 1024


class Server:
    def __init__(self, side, folder, argv, env, log_name="server.log", ready=(r"Done \(",)):
        self.side, self.folder, self.argv, self.env = side, folder, argv, env
        # What says the server is up. Horizon's "Done" can come before its Java side is up (a
        # warm start: the mods' content is cached), so its side waits for the mods' start too.
        self.ready = ready
        self.log = os.path.join(folder, log_name)
        self.proc = None

    def start(self):
        fifo = os.path.join(self.folder, "console")
        if not os.path.exists(fifo):
            os.mkfifo(fifo)
        logf = open(self.log, "a")
        self.offset = os.path.getsize(self.log)
        rfd = os.open(fifo, os.O_RDWR)  # held open both ways: no EOF while we write
        self.t0 = time.time()
        self.proc = subprocess.Popen(self.argv, cwd=self.folder, env=self.env, stdin=rfd, stdout=logf, stderr=subprocess.STDOUT, start_new_session=True)
        os.close(rfd)
        self.console = open(fifo, "w")
        for pattern in self.ready:
            if not self.wait_for(pattern, 1800, since=self.offset):
                raise RuntimeError("%s never started (%s), see %s" % (self.side, pattern, self.log))
        self.start_s = time.time() - self.t0
        m = re.search(r"Done \(([0-9.]+)s\)", self.text(self.offset))
        self.done_s = float(m.group(1)) if m else None
        return self.start_s

    def text(self, since=0):
        with open(self.log, errors="replace") as f:
            f.seek(since)
            return f.read()

    def size(self):
        return os.path.getsize(self.log)

    def wait_for(self, pattern, timeout, since=None):
        since = self.offset if since is None else since
        end = time.time() + timeout
        while time.time() < end:
            if re.search(pattern, self.text(since)):
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(0.25)
        return False

    def send(self, *cmds):
        for c in cmds:
            self.console.write(c + "\n")
        self.console.flush()

    def ask(self, cmd, pattern, timeout=30):
        at = self.size()
        self.send(cmd)
        end = time.time() + timeout
        while time.time() < end:
            m = re.search(pattern, self.text(at))
            if m:
                return m
            time.sleep(0.2)
        return None

    def game_time(self):
        m = self.ask("time query gametime", r"[Tt]he (?:game )?time is (\d+)")
        return int(m.group(1)) if m else None

    def tick_query(self):
        at = self.size()
        self.send("tick query")
        end = time.time() + 20
        while time.time() < end:
            t = self.text(at)
            a = re.search(r"Average time per tick: ([0-9.]+) ?ms", t)
            p = re.search(r"P50: ([0-9.]+) ?ms,? P95: ([0-9.]+) ?ms,? P99: ([0-9.]+) ?ms", t)
            if a and p:
                return [float(a.group(1))] + [float(x) for x in p.groups()]
            time.sleep(0.2)
        return None

    def dump(self, positions, timeout=120):
        """{(x, y, z): NBT dict or None} for each position (/data get block)."""
        at = self.size()
        self.send(*("data get block %d %d %d" % p for p in positions))
        want = set(positions)
        got = {}
        end = time.time() + timeout
        while time.time() < end and len(got) < len(want):
            for line in self.text(at).splitlines():
                m = re.search(r"(-?\d+), (-?\d+), (-?\d+) has the following block data: (\{.*\})\s*$", line)
                if m:
                    p = tuple(int(m.group(k)) for k in (1, 2, 3))
                    if p in want and p not in got:
                        try:
                            got[p] = parse_snbt(m.group(4))
                        except (AssertionError, IndexError) as e:
                            got[p] = {"_unparsed": m.group(4)[:200], "_err": str(e)}
            fails = len(re.findall(r"is not a block entity|not loaded|Unloaded|That position", self.text(at)))
            if len(got) + fails >= len(want):
                break
            time.sleep(0.3)
        return {p: got.get(p) for p in positions}

    def wait_loaded(self, chunks, timeout=600):
        """Until every chunk of the area answers a command (Horizon loads forced chunks in the
        background; vanilla's /forceload loads them at once). Setting air above the area to air
        fails harmlessly where loaded, and says "not loaded" where not."""
        x0, z0, x1, z1 = chunks
        probes = ["setblock %d %d %d minecraft:air" % (cx * 16, AREA_Y + 10, cz * 16) for cx in range(x0 >> 4, (x1 >> 4) + 1) for cz in range(z0 >> 4, (z1 >> 4) + 1)]
        end = time.time() + timeout
        while time.time() < end:
            at = self.size()
            self.send(*probes)
            time.sleep(2 + len(probes) * 0.01)
            t = self.text(at)
            answers = len(re.findall(r"not loaded|Could not set the block|Changed the block", t))
            if answers >= len(probes) and "not loaded" not in t:
                return True
            time.sleep(3)
        return False

    def stop(self, timeout=300):
        t = time.time()
        self.send("save-all flush")
        self.wait_for(r"Saved the game|Saving the game", 120, since=self.offset)
        self.send("stop")
        try:
            self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            os.killpg(self.proc.pid, signal.SIGKILL)
            self.proc.wait()
            return None
        finally:
            self.console.close()
        return time.time() - t

    def kill(self):
        if self.proc and self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except OSError:
                pass

    def jvm_pid(self):
        return self.proc.pid


def download(url, dest):
    if not os.path.exists(dest):
        with urllib.request.urlopen(url, timeout=120) as r, open(dest + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(dest + ".part", dest)
    return dest


def fabric_server(a, folder, java, vanilla_jar):
    """A real Fabric server in folder: Fabric's installer (downloaded) installs the loader."""
    cache = a.fabric_cache or os.path.join(folder, "..", "fabric-cache")
    os.makedirs(cache, exist_ok=True)
    loader = a.fabric_loader
    if not loader:
        with urllib.request.urlopen("%s/loader/26.3" % META, timeout=60) as r:
            loader = json.load(r)[0]["loader"]["version"]
    with urllib.request.urlopen("%s/installer" % META, timeout=60) as r:
        inst = next(x for x in json.load(r) if x.get("stable"))
    jar = download(inst["url"], os.path.join(cache, os.path.basename(inst["url"])))
    if not os.path.exists(os.path.join(folder, "fabric-server-launch.jar")):
        subprocess.run([java, "-jar", jar, "server", "-dir", folder, "-mcversion", "26.3", "-loader", loader], check=True, stdout=subprocess.DEVNULL)
        shutil.copy(vanilla_jar, os.path.join(folder, "server.jar"))
    return loader, inst["version"]


def pinned_fabric_api(a):
    """The Fabric API the bridge brings (crates/horizon-jvm/fabric-api.tsv), which Horizon runs in
    place of the folder's own: real Fabric gets the same jar, so both run one Fabric API."""
    import hashlib

    for line in open(os.path.join(R, "crates", "horizon-jvm", "fabric-api.tsv")):
        if line.strip() and not line.startswith("#"):
            name, url, sha = line.rstrip("\n").split("\t")[:3]
            cache = a.fabric_cache or os.path.join(a.scratch, "fabric-cache")
            os.makedirs(cache, exist_ok=True)
            path = download(url, os.path.join(cache, name))
            if hashlib.sha256(open(path, "rb").read()).hexdigest() != sha.strip():
                os.remove(path)
                sys.exit("%s: wrong SHA-256" % name)
            return path
    sys.exit("fabric-api.tsv pins no jar")


def mod_jars(a, side):
    """The jars a side gets: the folder's, with Fabric API swapped for the bridge's pinned one
    on real Fabric (Horizon skips the folder's Fabric API and runs its own)."""
    jars = [os.path.join(a.mods, j) for j in sorted(os.listdir(a.mods)) if j.endswith(".jar")]
    if side != "horizon":
        jars = [j for j in jars if not os.path.basename(j).startswith("fabric-api-")] + [a.fabric_api]
    return jars


def properties(folder, a, port):
    with open(os.path.join(folder, "server.properties"), "w") as f:
        f.write(
            "server-port=%d\nonline-mode=false\nwhite-list=false\nlevel-seed=%s\n"
            "gamemode=creative\nforce-gamemode=true\nallow-flight=true\nspawn-protection=0\npause-when-empty-seconds=0\n"
            "view-distance=%d\nsimulation-distance=%d\nmotd=bench\nsync-chunk-writes=true\n"
            % (port, a.seed, a.view_distance, a.view_distance)
        )
    with open(os.path.join(folder, "eula.txt"), "w") as f:
        f.write("eula=true\n")


def explorer(a, port, s):
    """One horizon-bots bot exploring for --explore-seconds; retried while the server refuses it."""
    exe = os.path.join(a.target, "horizon-bots")
    for attempt in range(6):
        at = s.size()
        proc = subprocess.Popen(
            [exe, "--server", "127.0.0.1:%d" % port, "--bots", "1", "--behaviour", "explore", "--fabric", "--seconds", str(a.explore_seconds),
             "--name", "Explore%d" % attempt, "--view-distance", str(a.view_distance), "--seed", "7", "--height", "200", "--report", os.path.join(a.out, "bot-%d.json" % port)],
            stdout=open(os.path.join(a.out, "bot-%d.log" % port), "a"),
            stderr=subprocess.STDOUT,
        )
        end = time.time() + 60
        while time.time() < end and proc.poll() is None:
            if re.search(r"Explore%d0 joined the game" % attempt, s.text(at)):
                return proc
            time.sleep(0.5)
        proc.kill()
        proc.wait()
        time.sleep(3)
    raise RuntimeError("%s: the exploring bot never joined (see %s)" % (s.side, a.out))


def bot_chunks(a, port):
    try:
        with open(os.path.join(a.out, "bot-%d.json" % port)) as f:
            return json.load(f).get("chunks")
    except (OSError, ValueError):
        return None


def summarize(windows):
    if not windows:
        return {}
    avg = statistics.mean(w[0] for w in windows)
    return {
        "avg": avg,
        "p50": statistics.median(w[1] for w in windows),
        "p95": statistics.median(w[2] for w in windows),
        "p99": max(w[3] for w in windows),
        "windows": len(windows),
    }


def area_check(before, after, line_of):
    """Problems after a restart: block entities gone or changed type, a line's items not conserved."""
    problems = []
    lines_b, lines_a = collections.defaultdict(collections.Counter), collections.defaultdict(collections.Counter)
    for p, nb in before.items():
        na = after.get(p)
        if nb is None:
            continue
        if na is None:
            problems.append("%s: block entity %s gone" % (fmt(p), nb.get("id")))
            continue
        if nb.get("id") != na.get("id"):
            problems.append("%s: %s became %s" % (fmt(p), nb.get("id"), na.get("id")))
        lost = sorted(set(nb) - set(na) - {"Item", "Items", "TransferCooldown", "components"})
        if lost:
            problems.append("%s: %s lost NBT %s" % (fmt(p), nb.get("id"), ", ".join(lost)))
        if "Speed" in nb and abs(float(nb.get("Speed", 0)) - float(na.get("Speed", 0))) > 1e-6:
            problems.append("%s: %s speed %s -> %s" % (fmt(p), nb.get("id"), nb.get("Speed"), na.get("Speed")))
        lines_b[line_of[p]].update(items_in(nb))
        lines_a[line_of[p]].update(items_in(na))
    for line in sorted(lines_b):
        if lines_b[line] != lines_a[line]:
            problems.append("line %s: items %s -> %s" % (line, dict(lines_b[line]), dict(lines_a[line])))
    return problems


def fmt(p):
    return "%d %d %d" % p


def run_side(a, side, java, jcmd, cmds, dump_at, line_of, chunks, port):
    folder = tempfile.mkdtemp(prefix="hz-bench-%s." % side, dir=a.scratch)
    res = {"side": side, "folder": folder}
    os.makedirs(os.path.join(folder, "mods" if side == "fabric" else "mods-java"))
    for j in mod_jars(a, side):
        shutil.copy(j, os.path.join(folder, "mods" if side == "fabric" else "mods-java"))
    properties(folder, a, port)
    jvm_opts = ["-Xmx%s" % a.xmx] if a.xmx else []
    if side == "fabric":
        res["loader"], res["installer"] = fabric_server(a, folder, java, a.vanilla_jar)
        argv = [java, *jvm_opts, "-jar", "fabric-server-launch.jar", "nogui"]
        env = dict(os.environ)
    else:
        argv = [a.horizon, "--resources", a.resources, "--dedicated", "--nogui"]
        env = dict(os.environ)
        for k in ("home", "lh", "data", "conf"):
            os.makedirs(os.path.join(folder, k), exist_ok=True)
        env.update(
            HORIZON_HOME=os.path.join(folder, "home"),
            HORIZON_LAUNCHER_HOME=os.path.join(folder, "lh"),
            XDG_DATA_HOME=os.path.join(folder, "data"),
            XDG_CONFIG_HOME=os.path.join(folder, "conf"),
            HORIZON_LAUNCHER_NO_SELF_UPDATE="1",
            HORIZON_NO_AUDIO="1",
            HORIZON_JAVA_MODS="1",
            HORIZON_ROOT=R,
            JAVA_HOME=os.path.dirname(os.path.dirname(java)),
            HORIZON_JAVA_HOME=os.path.dirname(os.path.dirname(java)),
            HORIZON_JVM_OPTIONS=" ".join(["-XX:+StartAttachListener", *jvm_opts, os.environ.get("HORIZON_JVM_OPTIONS", "")]).strip(),
        )
        if a.horizon_java_side:
            env["HORIZON_JAVA_SIDE"] = a.horizon_java_side
    ready = (r"Done \(", r"server's start hooks .*ran in") if side == "horizon" else (r"Done \(",)
    s = Server(side, folder, argv, env, ready=ready)
    try:
        log("%s: starting in %s" % (side, folder))
        res["start_s"] = s.start()
        res["done_says_s"] = s.done_s
        log("%s: up in %.1f s" % (side, res["start_s"]))
        s.send("tick freeze", "forceload add %d %d %d %d" % chunks)
        if not s.wait_loaded(chunks):
            raise RuntimeError("%s: the test area never loaded" % side)
        # The area, frozen: built in one go, each block in the same order on both sides.
        s.send(*cmds)
        time.sleep(3 + len(cmds) * 0.02)
        g0 = s.game_time()
        s.send("tick step %d" % a.parity_ticks)
        deadline = time.time() + a.parity_ticks / 20 * 20 + 60
        g1 = g0
        while time.time() < deadline:
            time.sleep(2)
            g1 = s.game_time()
            if g0 is not None and g1 is not None and g1 >= g0 + a.parity_ticks:
                time.sleep(1)
                g1 = s.game_time()
                break
        res["stepped"] = None if g0 is None or g1 is None else g1 - g0
        res["parity"] = s.dump(dump_at)
        # Still frozen: nothing may move now (vanilla's levels stand). A change here is a side
        # ticking what /tick freeze should stop.
        time.sleep(5)
        again = s.dump(dump_at)
        res["frozen_drift"] = [
            "%s (%s): %s -> %s" % (fmt(p), (b or {}).get("id"), items_in(b or {}), items_in(again.get(p) or {}))
            for p, b in res["parity"].items()
            if items_in(b or {}) != items_in(again.get(p) or {})
        ]
        log("%s: stepped %s ticks, %d/%d block entities dumped" % (side, res["stepped"], sum(v is not None for v in res["parity"].values()), len(dump_at)))
        # Load: items flowing.
        s.send("tick unfreeze")
        pid = s.jvm_pid()
        windows, rss, peak = [], [], 0
        t_end = time.time() + a.run_seconds
        time.sleep(5)
        while time.time() < t_end:
            q = s.tick_query()
            if q:
                windows.append(q)
            rss.append(rss_kb(pid))
            time.sleep(5)
        res["mspt_items"] = summarize(windows)
        res["rss_items_mb"] = statistics.mean(rss) / 1024 if rss else 0
        res["heap_items"] = heap(pid, jcmd)
        # Exploring: a bot (horizon-bots, answering Fabric API's registry sync) flies out over new
        # chunks, 200 blocks above spawn (a server pulls back a move into terrain, mountains too).
        windows2, rss2 = [], []
        ex = explorer(a, port, s)
        t_end = time.time() + a.explore_seconds
        time.sleep(5)
        while time.time() < t_end:
            q = s.tick_query()
            if q:
                windows2.append(q)
            rss2.append(rss_kb(pid))
            time.sleep(5)
        try:
            ex.wait(60)
        except subprocess.TimeoutExpired:
            ex.kill()
        res["explore_chunks"] = bot_chunks(a, port)
        res["mspt_explore"] = summarize(windows2)
        res["rss_explore_mb"] = statistics.mean(rss2) / 1024 if rss2 else 0
        res["heap_explore"] = heap(pid, jcmd)
        res["rss_peak_mb"] = rss_kb(pid, "VmHWM") / 1024
        res["mspt_all"] = summarize(windows + windows2)
        # Save: frozen, dumped, saved, stopped, restarted, dumped again.
        s.send("tick freeze")
        time.sleep(2)
        before = s.dump(dump_at)
        res["stop_s"] = s.stop()
        log("%s: stopped (%s s); restarting" % (side, res["stop_s"]))
        s.offset = s.size()
        res["restart_s"] = s.start()
        res["restart_done_says_s"] = s.done_s
        s.send("tick freeze")
        s.wait_loaded(chunks)
        after = s.dump(dump_at)
        res["save_problems"] = area_check(before, after, line_of)
        res["save_dumped"] = sum(v is not None for v in after.values())
        res["before_save"] = before
        s.stop()
        log("%s: save check: %d problems" % (side, len(res["save_problems"])))
    finally:
        s.kill()
    return res


def cross_open(a, java, horizon_res, dump_at, line_of, chunks, port):
    """Horizon's saved world, opened by the real Fabric server: the same area check."""
    folder = tempfile.mkdtemp(prefix="hz-bench-cross.", dir=a.scratch)
    os.makedirs(os.path.join(folder, "mods"))
    for j in mod_jars(a, "fabric"):
        shutil.copy(j, os.path.join(folder, "mods"))
    properties(folder, a, port)
    fabric_server(a, folder, java, a.vanilla_jar)
    shutil.copytree(os.path.join(horizon_res["folder"], "world"), os.path.join(folder, "world"))
    s = Server("cross", folder, [java, "-jar", "fabric-server-launch.jar", "nogui"], dict(os.environ))
    try:
        s.start()
        s.send("tick freeze")
        s.wait_loaded(chunks)
        after = s.dump(dump_at)
        problems = area_check(horizon_res["before_save"], after, line_of)
        s.stop()
    finally:
        s.kill()
    return {"folder": folder, "problems": problems, "dumped": sum(v is not None for v in after.values())}


def nbt_key_diff(fab, hor, dump_at):
    """Block entities whose NBT keys differ between the sides (what /data shows), not counted
    as parity diffs: a hint at saving or /data differences."""
    out = []
    for p in dump_at:
        f, h = fab.get(p), hor.get(p)
        if f and h and set(f) != set(h):
            out.append("%s (%s): only fabric %s, only horizon %s" % (fmt(p), f.get("id"), sorted(set(f) - set(h)), sorted(set(h) - set(f))))
    return out


def parity_diff(fab, hor, dump_at):
    diffs = []
    for p in dump_at:
        f, h = fab.get(p), hor.get(p)
        if f is None and h is None:
            continue
        if f is None or h is None:
            diffs.append("%s: fabric %s, horizon %s" % (fmt(p), f and f.get("id"), h and h.get("id")))
            continue
        if f.get("id") != h.get("id"):
            diffs.append("%s: fabric %s, horizon %s" % (fmt(p), f.get("id"), h.get("id")))
        fi, hi = items_in(f), items_in(h)
        if fi != hi:
            diffs.append("%s (%s): fabric %s, horizon %s" % (fmt(p), f.get("id"), fi, hi))
        if "Speed" in f or "Speed" in h:
            if float(f.get("Speed", 0)) != float(h.get("Speed", 0)):
                diffs.append("%s (%s): speed fabric %s, horizon %s" % (fmt(p), f.get("id"), f.get("Speed"), h.get("Speed")))
    return diffs


def machine(a, java):
    def sh(cmd):
        try:
            return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=20).stdout.strip()
        except Exception:
            return ""

    cpu = sh("grep -m1 'model name' /proc/cpuinfo | cut -d: -f2").strip()
    mem = sh("awk '/MemTotal/{printf \"%.1f GB\", $2/1048576}' /proc/meminfo")
    osn = sh(". /etc/os-release && echo $PRETTY_NAME")
    jv = sh("%s -version 2>&1 | grep -v Picked | head -1" % java)
    rev = sh("git -C %s rev-parse --short HEAD" % R)
    return "%s, %d threads, %s RAM, %s (kernel %s), %s, Horizon %s" % (cpu, os.cpu_count(), mem, osn, platform.release(), jv, rev)


def log(msg):
    print("[bench %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def report(a, res, diffs, keydiffs, cross, info, mods):
    cols = [r["side"] for r in res]
    names = {"fabric": "Real Fabric (Java server)", "horizon": "Horizon + bridge (`--dedicated`)"}

    def row(label, f):
        cells = []
        for r in res:
            try:
                cells.append(f(r))
            except (KeyError, TypeError, IndexError):
                cells.append("—")
        return "| %s | %s |" % (label, " | ".join(cells))

    def ms(key, q):
        return lambda r: "%.1f" % r[key][q]

    def hp(key):
        return lambda r: "%.0f / %.0f" % r[key]

    out = [
        "# Bridge vs real Fabric (%s)" % time.strftime("%Y-%m-%d %H:%M"),
        "",
        "Machine: %s" % info,
        "",
        "Mods (%d jars): %s. Both run Fabric API %s (the bridge's pinned one; Horizon skips the folder's)."
        % (len(mods), ", ".join(mods), os.path.basename(a.fabric_api)),
        "",
        "Scenario: seed %s, %d hopper lines (%d hoppers each), %d Create chute lines (%d chutes), %d Create kinetic lines "
        "(creative motor + 8 shafts); parity after %d stepped ticks; %d s of items running, then %d s of a bot "
        "exploring new chunks (horizon-bots, view distance %d)."
        % (a.seed, a.lines, a.hopper_len, a.chute_lines, a.chute_len, a.kinetic_lines, a.parity_ticks, a.run_seconds, a.explore_seconds, a.view_distance),
        "",
        "| | %s |" % " | ".join(names.get(c, c) for c in cols),
        "|---|" + "---|" * len(cols),
        row("Start, launch to ready (s; the server's own \"Done\" in brackets)", lambda r: "%.1f (%.1f)" % (r["start_s"], r["done_says_s"])),
        row("Restart on the saved world, launch to ready (s)", lambda r: "%.1f (%.1f)" % (r["restart_s"], r["restart_done_says_s"])),
        row("MSPT avg, items running", ms("mspt_items", "avg")),
        row("MSPT P50 / P95 / P99, items running", lambda r: "%.1f / %.1f / %.1f" % (r["mspt_items"]["p50"], r["mspt_items"]["p95"], r["mspt_items"]["p99"])),
        row("MSPT avg, exploring", ms("mspt_explore", "avg")),
        row("MSPT P50 / P95 / P99, exploring", lambda r: "%.1f / %.1f / %.1f" % (r["mspt_explore"]["p50"], r["mspt_explore"]["p95"], r["mspt_explore"]["p99"])),
        row("MSPT avg / P50 / P95 / P99, whole run", lambda r: "%.1f / %.1f / %.1f / %.1f" % tuple(r["mspt_all"][k] for k in ("avg", "p50", "p95", "p99"))),
        row("RSS items running / exploring (MB)", lambda r: "%.0f / %.0f" % (r["rss_items_mb"], r["rss_explore_mb"])),
        row("Peak RSS (MB)", lambda r: "%.0f" % r["rss_peak_mb"]),
        row("Java heap used / committed, items (MB)", hp("heap_items")),
        row("Java heap used / committed, exploring (MB)", hp("heap_explore")),
        row("Chunks the bot received exploring", lambda r: str(r["explore_chunks"])),
        row("Ticks stepped for parity", lambda r: str(r["stepped"])),
        row("Block entities dumped", lambda r: "%d / %d" % (sum(v is not None for v in r["parity"].values()), len(r["parity"]))),
        row("Moved while frozen (5 s after the step)", lambda r: str(len(r["frozen_drift"]))),
        row("Save check (restart)", lambda r: "OK" if not r["save_problems"] else "%d problems" % len(r["save_problems"])),
        row("Stop (save-all + stop, s)", lambda r: "%.1f" % r["stop_s"]),
    ]
    out.append("")
    if diffs is not None:
        out.append("**Parity diff count: %d** (containers' items and kinetic speeds after %d ticks, Fabric vs Horizon)." % (len(diffs), a.parity_ticks))
        out += ["- " + d for d in diffs[:60]]
        if len(diffs) > 60:
            out.append("- ... %d more in report.json" % (len(diffs) - 60))
        out.append("")
    for r in res:
        if r.get("frozen_drift"):
            out.append("**Moved while frozen, %s** (`/tick freeze` must stop it):" % r["side"])
            out += ["- " + d for d in r["frozen_drift"][:20]]
            out.append("")
    if keydiffs:
        out.append("NBT keys that differ (not counted above; `/data get block` or saving differences):")
        out += ["- " + d for d in keydiffs[:20]]
        out.append("")
    for r in res:
        if r.get("save_problems"):
            out.append("**Save problems, %s:**" % r["side"])
            out += ["- " + p for p in r["save_problems"][:40]]
            out.append("")
    if cross is not None:
        out.append("**Cross-open (Horizon's save opened by real Fabric): %s**" % ("OK" if not cross["problems"] else "%d problems" % len(cross["problems"])))
        out += ["- " + p for p in cross["problems"][:40]]
        out.append("")
    out += [
        "MSPT: `/tick query` every 5 s; each answer covers the last 100 ticks. avg = mean of the windows' averages, "
        "P50 and P95 = median of the windows' P50 and P95, P99 = the worst window's P99. RSS: the server process "
        "(Horizon's includes its JVM). Heap: `jcmd GC.heap_info` (Horizon's JVM is in its process).",
    ]
    return "\n".join(out) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--mods", required=True, help="folder of mod jars (both sides get the same)")
    p.add_argument("--seed", default="1234")
    p.add_argument("--lines", type=int, default=48, help="vanilla hopper lines")
    p.add_argument("--hopper-len", type=int, default=8)
    p.add_argument("--chute-lines", type=int, default=24, help="Create chute lines (0: no Create)")
    p.add_argument("--chute-len", type=int, default=4)
    p.add_argument("--kinetic-lines", type=int, default=16, help="Create creative motor + shafts lines")
    p.add_argument("--parity-ticks", type=int, default=200)
    p.add_argument("--run-seconds", type=int, default=120)
    p.add_argument("--explore-seconds", type=int, default=90)
    p.add_argument("--view-distance", type=int, default=10)
    p.add_argument("--sides", default="fabric,horizon")
    p.add_argument("--cross-open", action="store_true", help="open Horizon's saved world in real Fabric too")
    p.add_argument("--xmx", default="", help="the same -Xmx for both JVMs (default: the JVM's own, 25%% of RAM)")
    p.add_argument("--horizon-java-side", default="", help="HORIZON_JAVA_SIDE for Horizon (server: no headless client)")
    p.add_argument("--fabric-loader", default="0.20.1", help="the loader the bridge pins (crates/horizon-jvm/fabric-libs.tsv)")
    p.add_argument("--fabric-cache", default="", help="keep Fabric's installer here between runs")
    p.add_argument("--target", default=os.path.join(R, "target", "fast"))
    p.add_argument("--resources", default=os.path.join(R, ".local", "horizon-client", "26.3"))
    p.add_argument("--vanilla-jar", default=os.path.join(R, ".local", "minecraft", "26.3", "jars", "server.jar"))
    p.add_argument("--out", default="/tmp/hz-bench/last")
    p.add_argument("--keep", action="store_true", help="keep the servers' folders")
    p.add_argument("--build", action="store_true", help="build horizon-client (java-mods-dev) and horizon-bots first")
    a = p.parse_args()
    a.mods = os.path.abspath(os.path.expanduser(a.mods))
    a.horizon = os.path.join(a.target, "horizon-client")
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(a.out)
    a.scratch = tempfile.mkdtemp(prefix="hz-bench.")
    java_home = os.environ.get("JAVA_HOME", "")
    if not os.path.exists(os.path.join(java_home, "bin", "java")) or '"25' not in subprocess.run([os.path.join(java_home, "bin", "java"), "-version"], capture_output=True, text=True).stderr:
        cands = sorted(d for d in os.listdir("/usr/lib/jvm") if "25" in d) if os.path.isdir("/usr/lib/jvm") else []
        java_home = os.path.join("/usr/lib/jvm", cands[0]) if cands else java_home
    java, jcmd = os.path.join(java_home, "bin", "java"), os.path.join(java_home, "bin", "jcmd")
    os.environ["JAVA_HOME"] = java_home
    if a.build:
        subprocess.run(["cargo", "build", "--profile", "fast", "-p", "horizon-client", "-p", "horizon-client-lib", "-p", "horizon-bots", "--features", "horizon-client/java-mods-dev"], cwd=R, check=True)
    for need in (os.path.join(a.target, "horizon-bots"), a.vanilla_jar) + ((a.horizon,) if "horizon" in a.sides else ()):
        if not os.path.exists(need):
            sys.exit("missing %s (--build builds the binaries; .local needs the prepared 26.3)" % need)
    mods = sorted(j for j in os.listdir(a.mods) if j.endswith(".jar"))
    if a.chute_lines or a.kinetic_lines:
        if not any(j.startswith("create") for j in mods):
            log("no Create jar in the mods: vanilla hopper lines only")
            a.chute_lines = a.kinetic_lines = 0
    a.fabric_api = pinned_fabric_api(a)
    cmds, dump_at, line_of, chunks = scenario(a)
    res = []
    try:
        for i, side in enumerate(a.sides.split(",")):
            res.append(run_side(a, side, java, jcmd, cmds, dump_at, line_of, chunks, 25670 + i))
        by = {r["side"]: r for r in res}
        diffs = parity_diff(by["fabric"]["parity"], by["horizon"]["parity"], dump_at) if len(by) == 2 else None
        cross = cross_open(a, java, by["horizon"], dump_at, line_of, chunks, 25679) if a.cross_open and "horizon" in by else None
        keydiffs = nbt_key_diff(by["fabric"]["parity"], by["horizon"]["parity"], dump_at) if len(by) == 2 else []
        text = report(a, res, diffs, keydiffs, cross, machine(a, java), mods)
        with open(os.path.join(a.out, "report.md"), "w") as f:
            f.write(text)
        for r in res:
            for k in ("parity", "before_save"):
                r[k] = {fmt(p): v for p, v in r.get(k, {}).items()}
            if os.path.exists(os.path.join(r["folder"], "server.log")):
                shutil.copy(os.path.join(r["folder"], "server.log"), os.path.join(a.out, "%s.log" % r["side"]))
        with open(os.path.join(a.out, "report.json"), "w") as f:
            json.dump({"results": res, "parity_diffs": diffs, "nbt_key_diffs": keydiffs, "cross_open": cross}, f, indent=1, default=str)
        print(text)
    finally:
        if not a.keep:
            shutil.rmtree(a.scratch, ignore_errors=True)
        else:
            log("servers' folders kept in %s" % a.scratch)
    log("report: %s/report.md" % a.out)


if __name__ == "__main__":
    main()

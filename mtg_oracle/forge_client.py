"""Forge on this machine: its install, its decks folder, its processes.

The one module that touches Forge's files and runs Java. What the files and
the output *mean* is `forge_format`'s business; what gets stored is
`forge_data`'s. Nothing here knows about decks in the database — callers pass
rows in.

Three facts about Forge 2.0.14 shape it:

- `sim` loads decks only from Forge's decks folder: `-D` and absolute paths
  in `-d` are ignored. Constructed decks live in `decks/constructed`,
  and `-f Commander` looks in `decks/commander` instead.
- Forge resolves `res/` and `forge.profile.properties` against its working
  directory, so it runs with `cwd` = the install (or a run directory with
  `res/` linked in — how the integration test keeps Forge off the real
  decks folder).
- A game takes 2-8 s after about 6 s of JVM startup; Forge's own clock
  stops a game at 120 s.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from mtg_oracle import forge_format as ff

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = REPO_ROOT / "data" / "config.json"
LOGS_DIR = REPO_ROOT / "data" / "forge_logs"
INDEX_CACHE_PATH = REPO_ROOT / "data" / "forge_card_index.json"

DEFAULT_INSTALL_DIR = "tools/forge"
DEFAULT_DECKS_DIR = "%APPDATA%/Forge/decks/constructed"
DEFAULT_COMMANDER_DECKS_DIR = "%APPDATA%/Forge/decks/commander"
JVM_ARGS = ("-Xmx4096m", "-Dio.netty.tryReflectionSetAccessible=true",
            "-Dfile.encoding=UTF-8")
JAR_GLOB = "forge-gui-desktop-*-jar-with-dependencies.jar"
CARDS_ZIP = Path("res") / "cardsfolder" / "cardsfolder.zip"

# Startup, plus Forge's own 120 s per-game clock with some slack: a sim that
# outlives this is hung, not slow.
STARTUP_TIMEOUT_S = 90
PER_GAME_TIMEOUT_S = 150
MAX_GAMES = 100


class ForgeError(Exception):
    """Forge is missing, misconfigured, or refused; the message says which."""


# --- configuration --------------------------------------------------------

@dataclass(frozen=True)
class ForgeConfig:
    install_dir: Path
    decks_dir: Path            # constructed decks
    commander_decks_dir: Path  # what `-f Commander` reads
    java: str = "java"
    # Where Forge runs. The install unless a caller (the integration test)
    # prepared a directory with `res/` linked and its own profile.
    run_dir: Optional[Path] = None

    @property
    def cwd(self) -> Path:
        return self.run_dir or self.install_dir

    def decks_dir_for(self, game_type: str) -> Path:
        return self.commander_decks_dir if game_type == "commander" else self.decks_dir


def _expand(raw: str, base: Path) -> Path:
    path = Path(os.path.expandvars(os.path.expanduser(raw)))
    return path if path.is_absolute() else base / path


def load_config(path: Optional[Path] = None) -> ForgeConfig:
    """The `forge` key of data/config.json, with defaults for what's absent.

    Keys: `install_dir` (default tools/forge, relative to the repo),
    `decks_dir` (default %APPDATA%/Forge/decks/constructed),
    `commander_decks_dir` (default the `commander` sibling), `java`.
    A missing or unreadable file means all defaults.
    """
    try:
        with open(path or CONFIG_PATH, encoding="utf-8-sig") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    section = data.get("forge") if isinstance(data, dict) else None
    section = section if isinstance(section, dict) else {}
    return ForgeConfig(
        install_dir=_expand(section.get("install_dir") or DEFAULT_INSTALL_DIR, REPO_ROOT),
        decks_dir=_expand(section.get("decks_dir") or DEFAULT_DECKS_DIR, REPO_ROOT),
        commander_decks_dir=_expand(
            section.get("commander_decks_dir") or DEFAULT_COMMANDER_DECKS_DIR,
            REPO_ROOT),
        java=str(section.get("java") or "java"),
    )


@dataclass(frozen=True)
class ForgeInstall:
    """A validated install: everything a Forge command needs exists."""
    config: ForgeConfig
    jar: Path
    java: str
    version: str


def validate(config: ForgeConfig) -> ForgeInstall:
    """Check the install before any Forge command; ForgeError says what's wrong.

    Checked at use rather than at startup: the rest of the app works without
    Forge, and a missing install is only an error for the command that
    needed it.
    """
    install = config.install_dir
    if not install.is_dir():
        raise ForgeError(
            f"Forge is not installed at {install}. Unpack a Forge release "
            f"there, or set forge.install_dir in {CONFIG_PATH}.")
    jars = sorted(install.glob(JAR_GLOB))
    if not jars:
        raise ForgeError(f"no {JAR_GLOB} in {install} — is this a Forge "
                         f"desktop release?")
    if not (install / CARDS_ZIP).is_file():
        raise ForgeError(f"{install / CARDS_ZIP} is missing; the install "
                         f"looks incomplete.")
    java = shutil.which(config.java) or (
        config.java if Path(config.java).is_file() else None)
    if not java:
        raise ForgeError(
            f"java not found ({config.java!r}). Forge needs Java 17 or newer "
            f"on PATH, or set forge.java in {CONFIG_PATH}.")
    # A .bat/.cmd launcher runs through cmd.exe, which re-parses the command
    # line ("BatBadBut"), so deck-name arguments could become commands.
    if Path(java).suffix.lower() in (".bat", ".cmd"):
        raise ForgeError(
            f"java resolves to a batch script ({java}). Point forge.java in "
            f"{CONFIG_PATH} at java.exe itself.")
    return ForgeInstall(config=config, jar=jars[-1], java=java,
                        version=forge_version(install, jars[-1]))


def forge_version(install_dir: Path, jar: Path) -> str:
    """`2.0.14 (2026-08-08 22:49:26)`: the jar's version plus build.txt."""
    m = re.search(r"forge-gui-desktop-(.+?)-jar-with-dependencies", jar.name)
    version = m.group(1) if m else jar.stem
    try:
        built = (install_dir / "build.txt").read_text(encoding="utf-8").strip()
    except OSError:
        built = ""
    return f"{version} ({built})" if built else version


# --- the card index -------------------------------------------------------

def load_card_index(install: ForgeInstall,
                    cache_path: Optional[Path] = None) -> dict[str, dict]:
    """{casefolded Forge name: {"name", "ai"}} for every card Forge has.

    Scanning the 33.6k scripts takes about a second, so the result is cached
    under data/ keyed by Forge version and the zip's size and mtime: a new
    Forge release, or a patched cardsfolder, rebuilds it.
    """
    cache_path = cache_path or INDEX_CACHE_PATH
    zip_path = install.config.install_dir / CARDS_ZIP
    stat = zip_path.stat()
    key = f"{install.version}|{stat.st_size}|{int(stat.st_mtime)}"
    try:
        with open(cache_path, encoding="utf-8") as f:
            cached = json.load(f)
        if cached.get("key") == key and isinstance(cached.get("cards"), dict):
            return cached["cards"]
    except (OSError, ValueError, AttributeError):
        pass
    cards: dict[str, dict] = {}
    with zipfile.ZipFile(zip_path) as z:
        for member in z.namelist():
            if member.endswith("/"):
                continue
            parsed = ff.parse_card_script(z.read(member))
            if parsed:
                name, flag = parsed
                cards[name.casefold()] = {"name": name, "ai": flag}
    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = cache_path.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"key": key, "cards": cards}, f)
        os.replace(tmp, cache_path)
    except OSError:
        pass  # a cache that can't be written costs a second next time
    return cards


# --- editions -------------------------------------------------------------

EDITIONS_DIR = Path("res") / "editions"


def load_edition_index(install: ForgeInstall) -> dict:
    """`forge_format.edition_index` over the install's edition files.

    About 0.2 s for Forge 2.0.14's 678 files, so it is read per export
    rather than cached. An install without the folder gives an empty index:
    every printing then falls back to the name alone, which is how a row
    without a printing is written anyway.
    """
    folder = install.config.install_dir / EDITIONS_DIR
    editions = []
    for path in sorted(folder.glob("*.txt")) if folder.is_dir() else ():
        edition = ff.parse_edition(path.read_text(encoding="utf-8",
                                                  errors="replace"))
        if edition:
            editions.append(edition)
    return ff.edition_index(editions)


# --- deck files -----------------------------------------------------------

def deck_path(config: ForgeConfig, deck_name: str, game_type: str) -> Path:
    """Where the `.dck` for a deck name goes. Always inside the decks folder."""
    folder = config.decks_dir_for(game_type)
    path = folder / ff.safe_filename(deck_name)
    # safe_filename already makes escape impossible; this states the
    # invariant where the write happens, so a future change can't lose it.
    if path.resolve().parent != folder.resolve():
        raise ForgeError(f"refusing to write outside {folder}: {path}")
    return path


def write_deck(config: ForgeConfig, deck_name: str, deck_id: int,
               sections: dict, game_type: str, *,
               overwrite: bool = False) -> Path:
    """Write a `.dck`, refusing to replace one this project didn't write.

    An existing file is replaced only when it carries our marker, for the
    same deck, with its cards untouched since. Anything else — a deck the
    user built in Forge, another of our decks whose name sanitises to the
    same file, our export edited in Forge's editor — is refused unless
    `overwrite` is set.
    """
    path = deck_path(config, deck_name, game_type)
    if path.exists() and not overwrite:
        try:
            owner = ff.read_ownership(path.read_text(encoding="utf-8", errors="replace"))
        except OSError as e:
            raise ForgeError(f"cannot read {path}: {e}")
        if not owner.ours:
            raise ForgeError(
                f"{path} exists and was not written by mtg-oracle; not "
                f"overwriting it. Rename or delete it in Forge, or pass "
                f"--overwrite.")
        if owner.deck_id != deck_id:
            raise ForgeError(
                f"{path} holds another deck's export (deck #{owner.deck_id}) "
                f"whose name maps to the same file. Rename one of the decks, "
                f"or pass --overwrite.")
        if owner.edited:
            raise ForgeError(
                f"{path} was edited in Forge since it was exported; not "
                f"overwriting those edits. Pass --overwrite to replace it.")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(ff.render_dck(deck_name, deck_id, sections), encoding="utf-8")
    return path


# --- running Forge --------------------------------------------------------

def _base_command(install: ForgeInstall) -> list[str]:
    return [install.java, *JVM_ARGS, "-jar", str(install.jar)]


def launch_gui(install: ForgeInstall) -> int:
    """Start Forge's own GUI, detached from this process. Returns its pid."""
    java = install.java
    if sys.platform == "win32":
        # javaw has no console window; fall back to java if it isn't there.
        javaw = Path(java).with_name("javaw.exe")
        java = str(javaw) if javaw.is_file() else java
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        kwargs = {"creationflags": flags}
    else:
        kwargs = {"start_new_session": True}
    proc = subprocess.Popen(
        [java, *JVM_ARGS, "-jar", str(install.jar)],
        cwd=install.config.cwd, stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **kwargs)
    return proc.pid


@dataclass(frozen=True)
class SimRun:
    match_id: str
    stdout: str
    stderr: str
    returncode: int
    log_path: Path


def new_match_id() -> str:
    """Sortable and unique: `20260928-142200-3f9a1c`."""
    now = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    return f"{now}-{secrets.token_hex(3)}"


def run_sim(install: ForgeInstall, file_a: Path, file_b: Path, *,
            games: int, game_type: str,
            logs_dir: Optional[Path] = None) -> SimRun:
    """Run `sim` on two exported decks and keep its full output.

    Runs without `-q`: the full game log (casts, damage, zone changes) is
    what per-card statistics will be parsed from later, and it carries the
    same result lines. stdout and stderr are captured apart, because Forge's
    stack traces on stderr would otherwise split result lines in two.
    """
    if not 1 <= games <= MAX_GAMES:
        raise ForgeError(f"games must be 1..{MAX_GAMES}, got {games}")
    args = [*_base_command(install), "sim", "-d", file_a.name, file_b.name,
            "-n", str(games)]
    if game_type == "commander":
        args += ["-f", "Commander"]
    match_id = new_match_id()
    timeout = STARTUP_TIMEOUT_S + PER_GAME_TIMEOUT_S * games
    try:
        proc = subprocess.run(
            args, cwd=install.config.cwd, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout,
            stdin=subprocess.DEVNULL)
        stdout, stderr, code = proc.stdout, proc.stderr, proc.returncode
    except subprocess.TimeoutExpired as e:
        stdout = _as_text(e.stdout)
        stderr = _as_text(e.stderr) + f"\n[mtg-oracle] killed after {timeout}s"
        code = -1
    except OSError as e:
        raise ForgeError(f"could not start Forge: {e}")
    log_path = save_log(logs_dir or LOGS_DIR, match_id, args, stdout, stderr)
    if code == -1:
        raise ForgeError(f"Forge sim timed out after {timeout}s; "
                         f"output kept in {log_path}")
    return SimRun(match_id=match_id, stdout=stdout, stderr=stderr,
                  returncode=code, log_path=log_path)


def _as_text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return value


def save_log(logs_dir: Path, match_id: str, args: list[str],
             stdout: str, stderr: str) -> Path:
    """data/forge_logs/<match id>.log: the command, stdout, then stderr."""
    logs_dir.mkdir(parents=True, exist_ok=True)
    path = logs_dir / f"{match_id}.log"
    path.write_text(
        f"# mtg-oracle forge sim {match_id}\n# args: {args!r}\n"
        f"# ---- stdout ----\n{stdout}\n# ---- stderr ----\n{stderr}\n",
        encoding="utf-8")
    return path

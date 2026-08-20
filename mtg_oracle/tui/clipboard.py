"""Reading the system clipboard, on whatever platform this is.

Writing goes through Textual's own `App.copy_to_clipboard` (OSC 52). Reading
has no terminal escape, so it needs a platform tool — which is why this is
here and not inline in a command handler.
"""
from __future__ import annotations

import subprocess
import sys


def read_clipboard() -> str:
    """Best-effort cross-platform clipboard read.

    Returns the clipboard contents as a string. Raises RuntimeError if no
    supported tool is available on the current platform.
    """
    if sys.platform.startswith("win"):
        # Get-Clipboard is built into PowerShell on every modern Windows.
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Get-Clipboard"],
            capture_output=True, text=True, timeout=5,
        )
        if out.returncode == 0:
            return out.stdout
        raise RuntimeError(out.stderr or "Get-Clipboard failed")
    if sys.platform == "darwin":
        out = subprocess.run(["pbpaste"], capture_output=True, text=True, timeout=5)
        if out.returncode == 0:
            return out.stdout
        raise RuntimeError("pbpaste failed")
    # Linux / BSD: try xclip then wl-paste then xsel.
    for cmd in (
        ["xclip", "-selection", "clipboard", "-o"],
        ["wl-paste"],
        ["xsel", "--clipboard", "--output"],
    ):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        except FileNotFoundError:
            continue
        if out.returncode == 0:
            return out.stdout
    raise RuntimeError(
        "no clipboard tool found — install xclip, wl-clipboard, or xsel"
    )

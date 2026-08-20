"""The drag handle between the two panes.

Textual has no splitter widget, so this is the whole mechanism.
"""
from __future__ import annotations

from rich.text import Text
from textual import events
from textual.app import RenderResult
from textual.message import Message
from textual.widget import Widget


class PaneDivider(Widget):
    """A one-column drag handle between the two panes.

    Textual has no splitter widget, so this is the whole mechanism: capture
    the mouse on press, report the pointer's column while it moves, release
    on let-go. The App decides what a given column means for the nav width —
    the divider deliberately knows nothing about the panes it sits between.
    """

    DEFAULT_CSS = """
    PaneDivider {
        width: 1;
        height: 1fr;
        color: $accent;
    }
    PaneDivider:hover {
        color: $text;
    }
    """

    class Dragged(Message):
        """The divider moved to an absolute screen column, mid-drag."""

        def __init__(self, screen_x: int) -> None:
            self.screen_x = screen_x
            super().__init__()

    class DragEnded(Message):
        """The drag finished. Anything expensive belongs here, not in
        `Dragged`: a real drag emits a MouseMove per column crossed."""

    def render(self) -> RenderResult:
        return Text("\n".join(["│"] * max(1, self.size.height)), no_wrap=True)

    def on_resize(self, event: events.Resize) -> None:
        self.refresh()

    def on_mouse_down(self, event: events.MouseDown) -> None:
        self.capture_mouse()
        event.stop()

    def on_mouse_move(self, event: events.MouseMove) -> None:
        # Only while captured: otherwise a plain hover would resize the pane.
        if self.app.mouse_captured is self:
            self.post_message(self.Dragged(event.screen_x))
            event.stop()

    def on_mouse_up(self, event: events.MouseUp) -> None:
        was_dragging = self.app.mouse_captured is self
        self.release_mouse()
        if was_dragging:
            self.post_message(self.DragEnded())
        event.stop()

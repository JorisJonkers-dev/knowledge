"""Entry point: ``python -m note_import`` or the ``note-import`` console script."""

from __future__ import annotations

import sys

from note_import.cli import main

if __name__ == "__main__":  # pragma: no cover - module entry point
    sys.exit(main())

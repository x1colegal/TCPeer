#!/usr/bin/env python3
"""Repository checkout wrapper for tcppeer.devices_cli."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from tcppeer.devices_cli import main


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run the TCPeer operational CLI from the project root."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from tcppeer.cli import main


if __name__ == "__main__":
    main()

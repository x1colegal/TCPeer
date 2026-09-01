#!/usr/bin/env python3
"""Repository checkout wrapper for tcppeer.client."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from tcppeer.client import main


if __name__ == "__main__":
    main()

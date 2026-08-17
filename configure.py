#!/usr/bin/env python3
"""Run the interactive TCPeer configurator from the project root."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from tcppeer.configurator import main


if __name__ == "__main__":
    main()

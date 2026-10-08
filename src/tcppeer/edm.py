"""Bounded endpoint-dependent TCP mapping detection and port prediction."""

from __future__ import annotations

from dataclasses import dataclass
from statistics import median

# Keep clear of the default direct listener (coordinator 7443, direct 7444).
PROBE_PORT_OFFSETS = (100, 101, 102)
MAX_PORT_GUESSES = 17


@dataclass(frozen=True)
class EdmPrediction:
    samples: tuple[int, ...]
    predicted_port: int
    guesses: tuple[int, ...]


def predict_ports(samples: list[int] | tuple[int, ...]) -> EdmPrediction | None:
    """Recognize a stable EDM sequence and return a small ordered guess set."""
    ports = tuple(int(value) for value in samples if 0 < int(value) <= 65535)
    if len(ports) < 3 or len(set(ports)) == 1:
        return None
    deltas = tuple(right - left for left, right in zip(ports, ports[1:]))
    if not deltas or any(delta == 0 or abs(delta) > 256 for delta in deltas):
        return None
    if any((delta > 0) != (deltas[0] > 0) for delta in deltas):
        return None
    step = int(median(deltas))
    if any(abs(delta - step) > 2 for delta in deltas):
        return None
    predicted = ports[-1] + step
    if not 1 <= predicted <= 65535:
        return None
    guesses: list[int] = []
    for distance in range(0, 9):
        offsets = (0,) if distance == 0 else (distance, -distance)
        for offset in offsets:
            candidate = predicted + offset
            if 1 <= candidate <= 65535 and candidate not in guesses:
                guesses.append(candidate)
    return EdmPrediction(ports, predicted, tuple(guesses[:MAX_PORT_GUESSES]))


def parse_port_guesses(value: str | None) -> tuple[int, ...]:
    guesses: list[int] = []
    for item in (value or "").split(","):
        try:
            port = int(item)
        except ValueError:
            continue
        if 1 <= port <= 65535 and port not in guesses:
            guesses.append(port)
        if len(guesses) == MAX_PORT_GUESSES:
            break
    return tuple(guesses)


def format_port_guesses(values: tuple[int, ...] | list[int]) -> str:
    return ",".join(str(port) for port in parse_port_guesses(",".join(map(str, values))))

"""Secret-key challenge response for otherwise cleartext TCPeer control."""

from __future__ import annotations

import hashlib
import hmac

from .protocol import PROTOCOL


def authentication_proof(secret: str, network: str, peer_id: str, nonce: str) -> str:
    message = f"{PROTOCOL}\n{network}\n{peer_id}\n{nonce}".encode("ascii")
    return hmac.new(secret.encode("ascii"), message, hashlib.sha256).hexdigest()


def proof_matches(secret: str, network: str, peer_id: str, nonce: str, proof: str) -> bool:
    return hmac.compare_digest(authentication_proof(secret, network, peer_id, nonce), proof.casefold())

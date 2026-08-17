import ast
from pathlib import Path


SOURCE = Path("src/tcppeer")


def test_no_udp_socket_is_opened():
    for path in SOURCE.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Attribute):
                assert node.attr != "SOCK_DGRAM", f"UDP socket type referenced by {path}"
    android_source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in Path("android-app/src").rglob("*.kt")
    )
    assert "DatagramSocket" not in android_source
    assert "DatagramChannel" not in android_source


def test_only_secret_auth_uses_crypto_and_no_quic_webrtc_or_relay_implementation():
    forbidden_imports = {"ssl", "cryptography", "nacl", "aioquic", "aiortc"}
    for path in SOURCE.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module.split(".")[0])
        assert not imported & forbidden_imports, f"forbidden dependency in {path}"
        if path.name != "auth.py":
            assert "hashlib" not in imported and "hmac" not in imported, f"unexpected crypto in {path}"
    coordinator = (SOURCE / "coordinator.py").read_text(encoding="utf-8").casefold()
    assert "encode_data" not in coordinator
    assert "read_data" not in coordinator
    android_source = "\n".join(
        path.read_text(encoding="utf-8").casefold()
        for path in Path("android-app/src/main").rglob("*.kt")
    )
    for forbidden in ("sslsocket", "cipher", "webrtc", "quic", "relay socket"):
        assert forbidden not in android_source


def test_systemd_services_are_foreground_and_restart_on_failure():
    for path in Path("packaging/systemd").glob("*.service"):
        text = path.read_text(encoding="utf-8")
        assert "Type=simple" in text
        assert "Restart=on-failure" in text
        assert "network-online.target" in text
        assert "StandardOutput=journal" in text
        assert all(term not in text for term in ("nohup", "screen ", "tmux"))


def test_all_project_text_is_ascii_english_contract():
    suffixes = {".py", ".md", ".toml", ".service"}
    roots = [Path("src"), Path("tests"), Path("docs"), Path("examples"), Path("packaging"), Path("android-app/src")]
    paths = [Path("README.md"), Path("pyproject.toml"), Path("build.gradle.kts"), Path("settings.gradle.kts")]
    paths.extend(Path(name) for name in ("configure.py", "coordinator.py", "server.py", "cli.py"))
    paths.extend(path for root in roots for path in root.rglob("*"))
    for path in paths:
        if path.is_file() and path.suffix in suffixes | {".kt", ".kts", ".xml"}:
            path.read_text(encoding="ascii")

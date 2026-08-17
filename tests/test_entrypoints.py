import subprocess
import sys


def run(*arguments, input_text=None):
    return subprocess.run(
        (sys.executable, *arguments),
        input=input_text,
        text=True,
        capture_output=True,
        check=False,
    )


def test_root_runtime_entrypoints_load_the_package():
    for script in ("coordinator.py", "server.py", "cli.py"):
        result = run(script, "--help")
        assert result.returncode == 0, result.stderr
        assert "usage:" in result.stdout


def test_package_runtime_files_also_support_direct_execution():
    for script in (
        "src/tcppeer/coordinator.py",
        "src/tcppeer/server.py",
        "src/tcppeer/cli.py",
    ):
        result = run(script, "--help")
        assert result.returncode == 0, result.stderr
        assert "usage:" in result.stdout


def test_configurator_starts_without_relative_import_error():
    for script in ("configure.py", "src/tcppeer/configurator.py"):
        result = run(script, input_text="x\n")
        assert result.returncode != 0
        assert "must run as root" in result.stderr
        assert "ImportError" not in result.stderr


def test_root_scripts_do_not_shadow_tcppeer_package():
    result = subprocess.run(
        (sys.executable, "-c", "from tcppeer.transport import resolve_tcp_endpoints"),
        env={**__import__("os").environ, "PYTHONPATH": "src"},
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr

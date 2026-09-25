import errno

from tcppeer.transport import DirectConnector


def test_prebound_eaddrnotavail_is_retryable() -> None:
    error = OSError(errno.EADDRNOTAVAIL, "Cannot assign requested address")

    assert DirectConnector._retry_without_prebound(error)


def test_unrelated_prebound_error_remains_terminal() -> None:
    error = OSError(errno.ENETUNREACH, "Network is unreachable")

    assert not DirectConnector._retry_without_prebound(error)

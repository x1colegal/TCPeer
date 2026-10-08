import errno

from tcppeer.transport import DirectConnector


def test_prebound_eaddrnotavail_is_retryable() -> None:
    error = OSError(errno.EADDRNOTAVAIL, "Cannot assign requested address")

    assert DirectConnector._retry_without_prebound(error)


def test_unrelated_prebound_error_remains_terminal() -> None:
    error = OSError(errno.ENETUNREACH, "Network is unreachable")

    assert not DirectConnector._retry_without_prebound(error)


def test_eim_window_remains_ten_seconds() -> None:
    assert DirectConnector._effective_retry_window(1, 10.0) == 10.0


def test_edm_window_fits_every_one_second_candidate() -> None:
    assert DirectConnector._effective_retry_window(18, 10.0) >= 19.8

import errno

from tcppeer.transport import DirectConnector


def test_retry_window_ends_before_next_coordinator_punch_round() -> None:
    assert DirectConnector().retry_window == 12.0


def test_prebound_eaddrnotavail_is_retryable() -> None:
    error = OSError(errno.EADDRNOTAVAIL, "Cannot assign requested address")

    assert DirectConnector._retry_without_prebound(error)


def test_prebound_connection_refused_is_retryable() -> None:
    error = OSError(errno.ECONNREFUSED, "Connection refused")

    assert DirectConnector._retry_without_prebound(error)

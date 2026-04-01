import signal
import threading

import pytest

import opik.runner.activate as activate_module


@pytest.fixture(autouse=True)
def restore_signal_handlers_and_flag():
    prev_term = signal.getsignal(signal.SIGTERM)
    prev_int = signal.getsignal(signal.SIGINT)
    prev_flag = activate_module._shutdown_by_signal
    yield
    signal.signal(signal.SIGTERM, prev_term)
    signal.signal(signal.SIGINT, prev_int)
    activate_module._shutdown_by_signal = prev_flag


def test_install_signal_handlers__sets_shutdown_event_on_sigterm():
    shutdown_event = threading.Event()
    activate_module.install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGTERM)

    assert shutdown_event.is_set()


def test_install_signal_handlers__sets_shutdown_event_on_sigint():
    shutdown_event = threading.Event()
    activate_module.install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGINT)

    assert shutdown_event.is_set()


def test_install_signal_handlers__from_background_thread__does_not_raise():
    shutdown_event = threading.Event()
    errors = []

    def run():
        try:
            activate_module.install_signal_handlers(shutdown_event)
        except Exception as e:
            errors.append(e)

    t = threading.Thread(target=run)
    t.start()
    t.join()

    assert not errors
    assert not shutdown_event.is_set()


def test_signal_handler__sets_shutdown_by_signal_flag():
    activate_module._shutdown_by_signal = False
    shutdown_event = threading.Event()
    activate_module.install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGTERM)

    assert activate_module._shutdown_by_signal is True


def test_signal_then_warn__full_flow_no_warning(capsys):
    """End-to-end: install handlers, receive signal, atexit callback stays silent."""
    shutdown_event = threading.Event()
    activate_module.install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGTERM)

    assert shutdown_event.is_set()
    activate_module._warn_if_no_blocking_call()

    captured = capsys.readouterr()
    assert captured.err == ""


def test_no_signal_then_warn__full_flow_prints_warning(capsys):
    """End-to-end: install handlers, no signal received, atexit callback warns."""
    shutdown_event = threading.Event()
    activate_module.install_signal_handlers(shutdown_event)

    activate_module._warn_if_no_blocking_call()

    captured = capsys.readouterr()
    assert "exited without blocking" in captured.err

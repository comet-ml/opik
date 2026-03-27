import signal
import threading

from opik.runner.activate import install_signal_handlers


def test_install_signal_handlers__sets_shutdown_event_on_sigterm():
    shutdown_event = threading.Event()
    install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGTERM)

    assert shutdown_event.is_set()


def test_install_signal_handlers__sets_shutdown_event_on_sigint():
    shutdown_event = threading.Event()
    install_signal_handlers(shutdown_event)

    signal.raise_signal(signal.SIGINT)

    assert shutdown_event.is_set()


def test_install_signal_handlers__from_background_thread__does_not_raise():
    shutdown_event = threading.Event()
    errors = []

    def run():
        try:
            install_signal_handlers(shutdown_event)
        except Exception as e:
            errors.append(e)

    t = threading.Thread(target=run)
    t.start()
    t.join()

    assert not errors
    assert not shutdown_event.is_set()

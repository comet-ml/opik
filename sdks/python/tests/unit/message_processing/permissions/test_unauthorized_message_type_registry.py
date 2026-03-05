from unittest import mock

from opik.message_processing.permissions import unauthorized_message_type_registry

MESSAGE_TYPE_A = "message_type_a"
MESSAGE_TYPE_B = "message_type_b"
RETRY_INTERVAL = 60.0
NOW = 1000.0


class TestIsAuthorized:
    def test_is_authorized__message_type_not_in_registry__returns_true(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        assert registry.is_authorized(MESSAGE_TYPE_A) is True

    def test_is_authorized__message_added_within_retry_interval__returns_false(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL * 0.5):
            assert registry.is_authorized(MESSAGE_TYPE_A) is False

    def test_is_authorized__retry_interval_elapsed__returns_true(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL + 1.0):
            assert registry.is_authorized(MESSAGE_TYPE_A) is True

    def test_is_authorized__elapsed_time_equals_retry_interval__returns_true(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL):
            assert registry.is_authorized(MESSAGE_TYPE_A) is True

    def test_is_authorized__different_message_type_not_in_registry__returns_true(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL * 0.5):
            assert registry.is_authorized(MESSAGE_TYPE_B) is True

    def test_is_authorized__message_re_added_after_interval_elapsed__returns_false(
        self,
    ):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        later = NOW + RETRY_INTERVAL + 1.0
        registry.add(MESSAGE_TYPE_A, attempt_time=later)

        with mock.patch("time.time", return_value=later + RETRY_INTERVAL * 0.5):
            assert registry.is_authorized(MESSAGE_TYPE_A) is False


class TestIsAuthorizedWithMaxRetryCount:
    def test_is_authorized__max_retry_count_reached__returns_false(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL, max_retry_count=2
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=0
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=1
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=2

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL + 1.0):
            assert registry.is_authorized(MESSAGE_TYPE_A) is False

    def test_is_authorized__retry_count_below_max_and_interval_elapsed__returns_true(
        self,
    ):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL, max_retry_count=3
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=0
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=1

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL + 1.0):
            assert registry.is_authorized(MESSAGE_TYPE_A) is True

    def test_is_authorized__retry_count_below_max_within_interval__returns_false(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL, max_retry_count=3
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)  # retry_count=0

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL * 0.5):
            assert registry.is_authorized(MESSAGE_TYPE_A) is False

    def test_is_authorized__max_retry_count_not_set_and_interval_elapsed__returns_true(
        self,
    ):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL, max_retry_count=None
        )
        for _ in range(100):
            registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        with mock.patch("time.time", return_value=NOW + RETRY_INTERVAL + 1.0):
            assert registry.is_authorized(MESSAGE_TYPE_A) is True


class TestAdd:
    def test_add__message_type_not_in_registry__retry_count_is_zero(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        assert registry.registry[MESSAGE_TYPE_A].retry_count == 0

    def test_add__message_type_not_in_registry__last_attempt_at_is_stored(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        assert registry.registry[MESSAGE_TYPE_A].last_attempt_at == NOW

    def test_add__message_type_already_in_registry__retry_count_incremented(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        assert registry.registry[MESSAGE_TYPE_A].retry_count == 1

    def test_add__message_type_already_in_registry__last_attempt_at_updated(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        later = NOW + RETRY_INTERVAL + 1.0
        registry.add(MESSAGE_TYPE_A, attempt_time=later)

        assert registry.registry[MESSAGE_TYPE_A].last_attempt_at == later

    def test_add__attempt_time_not_provided__last_attempt_at_set_to_current_time(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )

        with mock.patch("time.time", return_value=NOW):
            registry.add(MESSAGE_TYPE_A)

        assert registry.registry[MESSAGE_TYPE_A].last_attempt_at == NOW

    def test_add__message_type_added_multiple_times__retry_count_incremented_cumulatively(
        self,
    ):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        for _ in range(5):
            registry.add(MESSAGE_TYPE_A, attempt_time=NOW)

        assert registry.registry[MESSAGE_TYPE_A].retry_count == 4

    def test_add__different_message_types__tracked_independently(self):
        registry = unauthorized_message_type_registry.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=RETRY_INTERVAL
        )
        registry.add(MESSAGE_TYPE_A, attempt_time=NOW)
        registry.add(MESSAGE_TYPE_B, attempt_time=NOW)
        registry.add(MESSAGE_TYPE_B, attempt_time=NOW)

        assert registry.registry[MESSAGE_TYPE_A].retry_count == 0
        assert registry.registry[MESSAGE_TYPE_B].retry_count == 1

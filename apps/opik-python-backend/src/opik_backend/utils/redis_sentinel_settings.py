"""Redis Sentinel settings, read from the same variables, defaults and bounds as the Java backend's config.yml."""
import os
import re
from dataclasses import dataclass
from typing import Optional
from urllib.parse import ParseResult, unquote, urlparse

SCHEMES = ("redis", "rediss")
MAX_RETRY_ATTEMPTS = 20
# ASCII digits only: \d and str.isdigit() also accept non-ASCII digits such as "١" or "²"
_DURATION = re.compile(r"^([0-9]+)\s*([a-z]+)$")
_DATABASE = re.compile(r"^[0-9]+$")
# The variables are shared with the Java backend, whose values are Dropwizard durations, so Python reads the same
# notation. Hours and days are left out: the 5m ceiling rejects any value written in them.
_DURATION_UNITS = {
    "ms": 0.001, "millisecond": 0.001, "milliseconds": 0.001,
    "s": 1, "second": 1, "seconds": 1,
    "m": 60, "min": 60, "mins": 60, "minute": 60, "minutes": 60,
}
_MAX_DURATION_SECONDS = 300


class RedisSentinelConfigError(ValueError):
    """Invalid OPIK_REDIS_SENTINEL_* or REDIS_URL settings, which must stop startup rather than be logged."""


def is_sentinel_enabled() -> bool:
    """OPIK_REDIS_SENTINEL_ENABLED, read like the Java backend reads it. Unset or empty means false."""
    value = os.getenv("OPIK_REDIS_SENTINEL_ENABLED", "").strip()
    return bool(value) and _parse_bool("OPIK_REDIS_SENTINEL_ENABLED", value)


def _parse_bool(name: str, value: str) -> bool:
    # The Java backend binds these through YAML 1.1 and Jackson, which take true/yes/on, false/no/off and integers
    lowered = value.lower()
    if lowered in ("true", "yes", "on"):
        return True
    if lowered in ("false", "no", "off"):
        return False
    try:
        return int(value) != 0
    except ValueError:
        raise RedisSentinelConfigError(f"{name} must be true or false") from None


@dataclass(frozen=True)
class RedisSentinelSettings:
    """Every Sentinel setting, parsed and validated in one place.

    REDIS_URL is the seed sentinel: its scheme selects TLS everywhere, and its credentials and database apply to the
    master data nodes. OPIK_REDIS_SENTINEL_SCAN_INTERVAL is Java-only: it is Redisson's polling period, while redis-py
    asks the sentinels for the master on every new connection instead.
    """

    master_name: str
    sentinels: list[tuple[str, int]]
    ssl: bool
    db: int
    username: Optional[str]
    password: Optional[str]
    sentinel_username: Optional[str]
    sentinel_password: Optional[str]
    retry_attempts: int
    retry_backoff_base: float
    retry_backoff_cap: float
    check_sentinels_list: bool
    connect_timeout: float
    timeout: float

    @classmethod
    def from_env(cls) -> "RedisSentinelSettings":
        """Reads and validates REDIS_URL and the OPIK_REDIS_SENTINEL_* variables.

        Raises RedisSentinelConfigError when a setting is missing or invalid, so callers can fail startup on it.
        """
        redis_url = os.getenv("REDIS_URL")
        if not redis_url:
            raise RedisSentinelConfigError("REDIS_URL must point at a sentinel when OPIK_REDIS_SENTINEL_ENABLED is true")
        seed = urlparse(redis_url)
        if seed.scheme not in SCHEMES:
            raise RedisSentinelConfigError("REDIS_URL must use the redis or rediss scheme when Sentinel is enabled")
        master_name = os.getenv("OPIK_REDIS_SENTINEL_MASTER_NAME", "").strip()
        if not master_name:
            raise RedisSentinelConfigError("OPIK_REDIS_SENTINEL_MASTER_NAME must not be blank when Sentinel is enabled")
        retry_attempts = _int_env("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "3")
        if not 0 <= retry_attempts <= MAX_RETRY_ATTEMPTS:
            raise RedisSentinelConfigError(
                f"OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS must be between 0 and {MAX_RETRY_ATTEMPTS}")
        retry_backoff_base = _duration_env("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "500ms", minimum=0.001)
        retry_backoff_cap = _duration_env("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "2s", minimum=0.001)
        if retry_backoff_base > retry_backoff_cap:
            raise RedisSentinelConfigError(
                "OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE must not exceed OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP")
        return cls(
            master_name=master_name,
            sentinels=_sentinels(seed),
            ssl=seed.scheme == "rediss",
            db=_database(seed),
            username=unquote(seed.username) if seed.username else None,
            password=unquote(seed.password) if seed.password else None,
            sentinel_username=os.getenv("OPIK_REDIS_SENTINEL_USERNAME") or None,
            sentinel_password=os.getenv("OPIK_REDIS_SENTINEL_PASSWORD") or None,
            retry_attempts=retry_attempts,
            retry_backoff_base=retry_backoff_base,
            retry_backoff_cap=retry_backoff_cap,
            check_sentinels_list=_bool_env("OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST", "true"),
            connect_timeout=_duration_env("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "10s"),
            timeout=_duration_env("OPIK_REDIS_SENTINEL_TIMEOUT", "5s"),
        )


def _sentinels(seed: ParseResult) -> list[tuple[str, int]]:
    """The seed first, then OPIK_REDIS_SENTINEL_NODES in order, without duplicates. Ports are never assumed."""
    if not seed.port:
        raise RedisSentinelConfigError("REDIS_URL must include the sentinel port when Sentinel is enabled")
    sentinels = [(seed.hostname, seed.port)]
    for node in os.getenv("OPIK_REDIS_SENTINEL_NODES", "").split(","):
        if node.strip():
            address = _sentinel_address(node.strip(), seed.scheme)
            if address not in sentinels:
                sentinels.append(address)
    return sentinels


def _database(seed: ParseResult) -> int:
    value = seed.path.lstrip("/") or "0"
    if not _DATABASE.match(value):
        raise RedisSentinelConfigError("REDIS_URL must select a non-negative database when Sentinel is enabled")
    return int(value)


def _sentinel_address(node: str, scheme: str) -> tuple[str, int]:
    # Credentials belong in OPIK_REDIS_SENTINEL_USERNAME/PASSWORD, so errors never echo the entry
    parsed = urlparse(node)
    if parsed.scheme not in SCHEMES:
        raise RedisSentinelConfigError("OPIK_REDIS_SENTINEL_NODES entries must use the redis or rediss scheme")
    if parsed.scheme != scheme:
        raise RedisSentinelConfigError("OPIK_REDIS_SENTINEL_NODES entries must use the same scheme as REDIS_URL")
    if parsed.username or parsed.password:
        raise RedisSentinelConfigError(
            "OPIK_REDIS_SENTINEL_NODES entries must not carry credentials, "
            "use OPIK_REDIS_SENTINEL_USERNAME/PASSWORD"
        )
    try:
        port = parsed.port
    except ValueError as exception:
        raise RedisSentinelConfigError(
            "OPIK_REDIS_SENTINEL_NODES entries must include a host and a port between 1 and 65535") from exception
    if not parsed.hostname or not port:
        raise RedisSentinelConfigError(
            "OPIK_REDIS_SENTINEL_NODES entries must include a host and a port between 1 and 65535")
    return parsed.hostname, port


def _required_env(name: str, default: str) -> str:
    # Like the Java backend, an exported but empty variable is an error rather than a fallback to the default
    value = os.getenv(name, default).strip()
    if not value:
        raise RedisSentinelConfigError(f"{name} must not be empty when Sentinel is enabled")
    return value


def _int_env(name: str, default: str) -> int:
    value = _required_env(name, default)
    try:
        return int(value)
    except ValueError as exception:
        raise RedisSentinelConfigError(f"{name} must be an integer") from exception


def _bool_env(name: str, default: str) -> bool:
    return _parse_bool(name, _required_env(name, default))


def _duration_env(name: str, default: str, *, minimum: float = 0.1, maximum: float = _MAX_DURATION_SECONDS) -> float:
    match = _DURATION.match(_required_env(name, default).lower())
    if not match or match.group(2) not in _DURATION_UNITS:
        raise RedisSentinelConfigError(f"{name} must be a duration such as 500ms, 5s or 1m")
    seconds = int(match.group(1)) * _DURATION_UNITS[match.group(2)]
    if not minimum <= seconds <= maximum:
        raise RedisSentinelConfigError(f"{name} must be between {minimum}s and {maximum}s")
    return seconds

import httpx

KEEPALIVE_EXPIRY_SECONDS = 10
CONNECT_TIMEOUT_SECONDS = 20
READ_TIMEOUT_SECONDS = 100
WRITE_TIMEOUT_SECONDS = 100
POOL_TIMEOUT_SECONDS = 20


def get() -> httpx.Client:
    limits = httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS)

    timeout = httpx.Timeout(
        connect=CONNECT_TIMEOUT_SECONDS,
        read=READ_TIMEOUT_SECONDS,
        write=WRITE_TIMEOUT_SECONDS,
        pool=POOL_TIMEOUT_SECONDS,
    )

    return httpx.Client(limits=limits, timeout=timeout)

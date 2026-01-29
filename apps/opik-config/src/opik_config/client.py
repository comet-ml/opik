from typing import Any

import requests

class BackendUnavailableError(Exception):
    pass



class ConfigClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._session = requests.Session()

    def get_values(self, keys: list[str], experiment_id: str | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {"keys": keys}
        if experiment_id:
            payload["experiment_id"] = experiment_id

        try:
            response = self._session.post(
                f"{self.base_url}/config/get",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException as e:
            raise BackendUnavailableError(f"Failed to fetch config: {e}") from e

        data = response.json()
        result: dict[str, Any] = {}
        for key, value_data in data.get("values", {}).items():
            if value_data is not None:
                result[key] = value_data["value"]
        return result

    def set_value(
        self, key: str, value: Any, if_not_exists: bool = False, is_default: bool = False
    ) -> None:
        try:
            payload: dict[str, Any] = {"key": key, "value": value}
            if if_not_exists:
                payload["if_not_exists"] = True
            if is_default:
                payload["is_default"] = True
            response = self._session.post(
                f"{self.base_url}/config/set",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException:
            pass  # Silently fail if backend unavailable

# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2024 Comet ML INC
#  This file can not be copied and/or distributed
#  without the express permission of Comet ML Inc.
# *******************************************************
import json
import logging
from typing import Any, Dict, Optional

from ..logging_messages import (
    PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES,
    PARSE_API_KEY_EMPTY_KEY,
    PARSE_API_KEY_TOO_MANY_PARTS,
)
from .base64_helper import decode_base64

LOGGER = logging.getLogger(__name__)

DELIMITER_CHAR = "*"


class OpikApiKey:
    """
    This is Opik API key parser module which is able to parse enhanced API key format. The format as following:
    initial 25 chars apiKey + DELIMITER_CHAR + base64 encoded OPIK_BASE_URL and other attributes as JSON dictionary.

    The logic of this module is shared among comet_ml, comet_mpm, and opik projects.
    Please do not change this module without synchronization with mentioned projects.
    """

    def __init__(
        self,
        api_key_raw: str,
        api_key: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None,
    ):
        self._api_key_raw = api_key_raw
        self._api_key = api_key
        self._attributes = attributes

    @property
    def api_key(self) -> Optional[str]:
        return self._api_key_raw

    @property
    def short_api_key(self) -> Optional[str]:
        if self._api_key is not None:
            return self._api_key
        return self._api_key_raw

    @property
    def base_url(self) -> Optional[str]:
        if self["baseUrl"] is not None:
            return str(self["baseUrl"])
        else:
            return None

    def __getitem__(self, key: str) -> Any:
        if self._attributes is not None:
            return self._attributes.get(key, None)

        return None


def parse_api_key(raw_key: str) -> Optional[OpikApiKey]:
    if raw_key is None or len(raw_key) == 0:
        LOGGER.debug(PARSE_API_KEY_EMPTY_KEY)
        return None

    parts = raw_key.split(DELIMITER_CHAR)
    size = len(parts)
    if size == 1:
        LOGGER.debug("Opik API key doesn't have attributes associated")
        return OpikApiKey(api_key_raw=raw_key)
    elif size == 2:
        attr_string = parts[1]
        if len(attr_string) > 0:
            data = decode_base64(attr_string)
            attributes = json.loads(data)
        else:
            # edge case - delimiter found but no encoded JSON afterward
            LOGGER.warning(PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES % raw_key)
            raw_key = parts[0]  # remove obsolete delimiter
            attributes = None

        return OpikApiKey(api_key_raw=raw_key, api_key=parts[0], attributes=attributes)

    LOGGER.warning(PARSE_API_KEY_TOO_MANY_PARTS, size, raw_key)
    return None

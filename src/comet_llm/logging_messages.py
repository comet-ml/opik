# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

FAILED_TO_SEND_DATA_TO_SERVER = "Failed to send data to server"

UNABLE_TO_LOG_TO_NON_LLM_PROJECT = "Failed to send prompt to the specified project as it is not an LLM project, please specify a different project name."

API_KEY_NOT_FOUND_MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to %s or as an environment
    variable named COMET_API_KEY
    """

API_KEY_NOT_CONFIGURED = """
    CometLLM requires an API key. Please provide it as the
    as an environment variable named COMET_API_KEY
    """

NON_ALLOWED_SCORE = "Score can only be 0 or 1 when calling 'log_user_feedback'"

METADATA_KEY_COLLISION_DURING_DEEPMERGE = (
    "Chain or prompt metadata value for the sub-key '%s' was overwritten from '%s' to '%s' during the deep merge",
)

INVALID_TIMESTAMP = "Invalid timestamp: %s. Timestamp must be in seconds if specified."

GLOBAL_CHAIN_NOT_INITIALIZED = "Global chain is not initialized for this thread. Initialize it with `comet_llm.start_chain(...)` if you wish to use %s"

PARSE_API_KEY_EMPTY_KEY = "Can not parse empty Comet API key"

PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES = (
    "Expected attributes not found in the Comet API key: %r"
)

PARSE_API_KEY_TOO_MANY_PARTS = "Too many parts (%d) found in the Comet API key: %r"

BASE_URL_MISMATCH_CONFIG_API_KEY = "Comet URL conflict detected between config (%r) and API Key (%r). SDK will use config URL. Resolve by either removing config URL or set it to the same value."

MESSAGE_IS_NOT_JSON_SERIALIZABLE = "Message is not JSON serializable"

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


UNABLE_TO_LOG_TO_NON_LLM_PROJECT = "Failed to send prompt to the specified project as it is not an LLM project, please specify a different project name."

API_KEY_NOT_FOUND_MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to %s or as an environment
    variable named COMET_API_KEY
    """

NON_ALLOWED_SCORE = "Score can only be 0 or 1 when calling 'log_user_feedback'"

METADATA_KEY_COLLISION_DURING_DEEPMERGE = (
    "Chain or prompt metadata value for the sub-key '%s' was overwritten from '%s' to '%s' during the deep merge",
)

INVALID_TIMESTAMP = "Invalid timestamp: %s. Timestamp must be in seconds if specified."

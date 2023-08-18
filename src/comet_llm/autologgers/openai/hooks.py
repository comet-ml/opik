# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at http://www.comet.ml
#  Copyright (C) 2015-2021 Comet ML INC
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import logging
from typing import TYPE_CHECKING, Any, Dict, Iterator, Tuple, Callable

from .. import config
from comet_llm.chains import chain, span
from comet_llm.chains import state as chains_state
from comet_llm.chains import api as chains_api

from . import chat_completion_parsers, context


if TYPE_CHECKING:  # pragma: no cover
    import comet_ml

LOGGER = logging.getLogger(__name__)


def before_chat_completion_create(original: Callable, *args, **kwargs):
    if not config.enabled():
        return
    
    inputs, metadata = chat_completion_parsers.parse_create_arguments(kwargs)

    if chains_state.global_chain_exists():
        chain_ = chains_state.get_global_chain()
    else:
        chain_ = chain.Chain(
            inputs=inputs,
            metadata=metadata,
            experiment_info=config.get_experiment_info()
        )
        context.CONTEXT.chain = chain_

    span_ = span.Span(
        inputs=inputs,
        metadata=metadata,
        chain=chain_,
        category="openai-chat-completion"
    )

    span_.__api__start__()

    context.CONTEXT.span = span_

@context.clear_on_end
def after_chat_completion_create(original, return_value, *args, **kwargs):
    if not config.enabled():
        return
    
    outputs, metadata = chat_completion_parsers.parse_create_result(return_value)

    span_ = context.CONTEXT.span
    span_.set_outputs(
        outputs=outputs,
        metadata=metadata
    )
    span_.__api__end__()

    if context.CONTEXT.chain is not None:
        chain_ = context.CONTEXT.chain
        chain_.set_outputs(
            outputs=outputs,
            metadata=metadata
        )
        chains_api.log_chain(chain_)


@context.clear_on_end
def after_exception_chat_completion_create(original, exception, *args, **kwargs):
    pass

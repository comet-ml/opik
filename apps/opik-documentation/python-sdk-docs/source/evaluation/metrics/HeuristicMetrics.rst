Heuristic Metrics
=================

.. currentmodule:: opik.evaluation.metrics

This section lists the text-similarity, readability, safety, and sentiment metrics
that can be composed into larger evaluation suites. Several metrics rely on optional
dependencies (for example, ``bert-score``, ``sacrebleu``, ``fasttext``, ``nltk``);
install the relevant packages before scoring.

Sentence & Token Overlap
------------------------

.. autoclass:: SentenceBLEU
    :special-members: __init__
    :members: score

.. autoclass:: CorpusBLEU
    :special-members: __init__
    :members: score

.. autoclass:: GLEU
    :special-members: __init__
    :members: score

.. autoclass:: ROUGE
    :special-members: __init__
    :members: score

.. autoclass:: ChrF
    :special-members: __init__
    :members: score

.. autoclass:: METEOR
    :special-members: __init__
    :members: score

.. autoclass:: BERTScore
    :special-members: __init__
    :members: score

Distribution Comparisons
------------------------

.. autoclass:: JSDivergence
    :special-members: __init__
    :members: score

.. autoclass:: JSDistance
    :special-members: __init__
    :members: score

.. autoclass:: KLDivergence
    :special-members: __init__
    :members: score

Rank & Readability
------------------

.. autoclass:: SpearmanRanking
    :special-members: __init__
    :members: score

.. autoclass:: Readability
    :special-members: __init__
    :members: score

.. autoclass:: Tone
    :special-members: __init__
    :members: score

Prompt Safety & Sentiment
-------------------------

.. autoclass:: PromptInjection
    :special-members: __init__
    :members: score

.. autoclass:: LanguageAdherenceMetric
    :special-members: __init__
    :members: score

.. autoclass:: Sentiment
    :special-members: __init__
    :members: score

.. autoclass:: VADERSentiment
    :special-members: __init__
    :members: score

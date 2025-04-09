"""
A C extension module for fast computation of:
- Levenshtein (edit) distance and edit sequence manipulation
- string similarity
- approximate median strings, and generally string averaging
- string sequence and set similarity

Levenshtein has a some overlap with difflib (SequenceMatcher).  It
supports only strings, not arbitrary sequence types, but on the
other hand it's much faster.

It supports both normal and Unicode strings, but can't mix them, all
arguments to a function (method) have to be of the same type (or its
subclasses).
"""

from __future__ import annotations

__author__: str = "Max Bachmann"
__license__: str = "GPL"
__version__: str = "0.27.1"

import rapidfuzz.distance.Hamming as _Hamming
import rapidfuzz.distance.Indel as _Indel
import rapidfuzz.distance.Jaro as _Jaro
import rapidfuzz.distance.JaroWinkler as _JaroWinkler
import rapidfuzz.distance.Levenshtein as _Levenshtein
from rapidfuzz.distance import (
    Editops as _Editops,
)
from rapidfuzz.distance import (
    Opcodes as _Opcodes,
)

from Levenshtein.levenshtein_cpp import (
    median,
    median_improve,
    quickmedian,
    seqratio,
    setmedian,
    setratio,
)

__all__ = [
    "quickmedian",
    "median",
    "median_improve",
    "setmedian",
    "setratio",
    "seqratio",
    "distance",
    "ratio",
    "hamming",
    "jaro",
    "jaro_winkler",
    "editops",
    "opcodes",
    "matching_blocks",
    "apply_edit",
    "subtract_edit",
    "inverse",
]


def distance(s1, s2, *, weights=(1, 1, 1), processor=None, score_cutoff=None, score_hint=None):
    """
    Calculates the minimum number of insertions, deletions, and substitutions
    required to change one sequence into the other according to Levenshtein with custom
    costs for insertion, deletion and substitution

    Parameters
    ----------
    s1 : Sequence[Hashable]
        First string to compare.
    s2 : Sequence[Hashable]
        Second string to compare.
    weights : Tuple[int, int, int] or None, optional
        The weights for the three operations in the form
        (insertion, deletion, substitution). Default is (1, 1, 1),
        which gives all three operations a weight of 1.
    processor: callable, optional
        Optional callable that is used to preprocess the strings before
        comparing them. Default is None, which deactivates this behaviour.
    score_cutoff : int, optional
        Maximum distance between s1 and s2, that is
        considered as a result. If the distance is bigger than score_cutoff,
        score_cutoff + 1 is returned instead. Default is None, which deactivates
        this behaviour.
    score_hint : int, optional
        Expected distance between s1 and s2. This is used to select a
        faster implementation. Default is None, which deactivates this behaviour.

    Returns
    -------
    distance : int
        distance between s1 and s2

    Raises
    ------
    ValueError
        If unsupported weights are provided a ValueError is thrown

    Examples
    --------
    Find the Levenshtein distance between two strings:

    >>> from Levenshtein import distance
    >>> distance("lewenstein", "levenshtein")
    2

    Setting a maximum distance allows the implementation to select
    a more efficient implementation:

    >>> distance("lewenstein", "levenshtein", score_cutoff=1)
    2

    It is possible to select different weights by passing a `weight`
    tuple.

    >>> distance("lewenstein", "levenshtein", weights=(1,1,2))
    3
    """
    return _Levenshtein.distance(
        s1,
        s2,
        weights=weights,
        processor=processor,
        score_cutoff=score_cutoff,
        score_hint=score_hint,
    )


def ratio(s1, s2, *, processor=None, score_cutoff=None):
    """
    Calculates a normalized indel similarity in the range [0, 1].
    The indel distance calculates the minimum number of insertions and deletions
    required to change one sequence into the other.

    This is calculated as ``1 - (distance / (len1 + len2))``

    Parameters
    ----------
    s1 : Sequence[Hashable]
        First string to compare.
    s2 : Sequence[Hashable]
        Second string to compare.
    processor: callable, optional
        Optional callable that is used to preprocess the strings before
        comparing them. Default is None, which deactivates this behaviour.
    score_cutoff : float, optional
        Optional argument for a score threshold as a float between 0 and 1.0.
        For norm_sim < score_cutoff 0 is returned instead. Default is 0,
        which deactivates this behaviour.

    Returns
    -------
    norm_sim : float
        normalized similarity between s1 and s2 as a float between 0 and 1.0

    Examples
    --------
    Find the normalized Indel similarity between two strings:

    >>> from Levenshtein import ratio
    >>> ratio("lewenstein", "levenshtein")
    0.85714285714285

    Setting a score_cutoff allows the implementation to select
    a more efficient implementation:

    >>> ratio("lewenstein", "levenshtein", score_cutoff=0.9)
    0.0

    When a different processor is used s1 and s2 do not have to be strings

    >>> ratio(["lewenstein"], ["levenshtein"], processor=lambda s: s[0])
    0.8571428571428572
    """
    return _Indel.normalized_similarity(s1, s2, processor=processor, score_cutoff=score_cutoff)


def hamming(s1, s2, *, pad=True, processor=None, score_cutoff=None):
    """
    Calculates the Hamming distance between two strings.
    The hamming distance is defined as the number of positions
    where the two strings differ. It describes the minimum
    amount of substitutions required to transform s1 into s2.

    Parameters
    ----------
    s1 : Sequence[Hashable]
        First string to compare.
    s2 : Sequence[Hashable]
        Second string to compare.
    pad : bool, optional
       should strings be padded if there is a length difference.
       If pad is False and strings have a different length
       a ValueError is thrown instead. Default is True.
    processor: callable, optional
        Optional callable that is used to preprocess the strings before
        comparing them. Default is None, which deactivates this behaviour.
    score_cutoff : int or None, optional
        Maximum distance between s1 and s2, that is
        considered as a result. If the distance is bigger than score_cutoff,
        score_cutoff + 1 is returned instead. Default is None, which deactivates
        this behaviour.

    Returns
    -------
    distance : int
        distance between s1 and s2

    Raises
    ------
    ValueError
        If s1 and s2 have a different length
    """
    return _Hamming.distance(s1, s2, pad=pad, processor=processor, score_cutoff=score_cutoff)


def jaro(s1, s2, *, processor=None, score_cutoff=None) -> float:
    """
    Calculates the jaro similarity

    Parameters
    ----------
    s1 : Sequence[Hashable]
        First string to compare.
    s2 : Sequence[Hashable]
        Second string to compare.
    processor: callable, optional
        Optional callable that is used to preprocess the strings before
        comparing them. Default is None, which deactivates this behaviour.
    score_cutoff : float, optional
        Optional argument for a score threshold as a float between 0 and 1.0.
        For ratio < score_cutoff 0 is returned instead. Default is None,
        which deactivates this behaviour.

    Returns
    -------
    similarity : float
        similarity between s1 and s2 as a float between 0 and 1.0
    """
    return _Jaro.similarity(s1, s2, processor=processor, score_cutoff=score_cutoff)


def jaro_winkler(s1, s2, *, prefix_weight=0.1, processor=None, score_cutoff=None) -> float:
    """
    Calculates the jaro winkler similarity

    Parameters
    ----------
    s1 : Sequence[Hashable]
        First string to compare.
    s2 : Sequence[Hashable]
        Second string to compare.
    prefix_weight : float, optional
        Weight used for the common prefix of the two strings.
        Has to be between 0 and 0.25. Default is 0.1.
    processor: callable, optional
        Optional callable that is used to preprocess the strings before
        comparing them. Default is None, which deactivates this behaviour.
    score_cutoff : float, optional
        Optional argument for a score threshold as a float between 0 and 1.0.
        For ratio < score_cutoff 0 is returned instead. Default is None,
        which deactivates this behaviour.

    Returns
    -------
    similarity : float
        similarity between s1 and s2 as a float between 0 and 1.0

    Raises
    ------
    ValueError
        If prefix_weight is invalid
    """
    return _JaroWinkler.similarity(
        s1,
        s2,
        prefix_weight=prefix_weight,
        processor=processor,
        score_cutoff=score_cutoff,
    )


# assign attributes to function. This allows rapidfuzz to call them more efficiently
# we can't directly copy the functions + replace the docstrings, since this leads to
# crashes on PyPy
distance._RF_OriginalScorer = distance
ratio._RF_OriginalScorer = ratio
hamming._RF_OriginalScorer = hamming
jaro._RF_OriginalScorer = jaro
jaro_winkler._RF_OriginalScorer = jaro_winkler

distance._RF_ScorerPy = _Levenshtein.distance._RF_ScorerPy
ratio._RF_ScorerPy = _Indel.normalized_similarity._RF_ScorerPy
hamming._RF_ScorerPy = _Hamming.distance._RF_ScorerPy
jaro._RF_ScorerPy = _Jaro.similarity._RF_ScorerPy
jaro_winkler._RF_ScorerPy = _JaroWinkler.similarity._RF_ScorerPy

if hasattr(_Levenshtein.distance, "_RF_Scorer"):
    distance._RF_Scorer = _Levenshtein.distance._RF_Scorer
if hasattr(_Indel.normalized_similarity, "_RF_Scorer"):
    ratio._RF_Scorer = _Indel.normalized_similarity._RF_Scorer
if hasattr(_Hamming.distance, "_RF_Scorer"):
    hamming._RF_Scorer = _Hamming.distance._RF_Scorer
if hasattr(_Jaro.similarity, "_RF_Scorer"):
    jaro._RF_Scorer = _Jaro.similarity._RF_Scorer
if hasattr(_JaroWinkler.similarity, "_RF_Scorer"):
    jaro_winkler._RF_Scorer = _JaroWinkler.similarity._RF_Scorer


def editops(*args):
    """
    Find sequence of edit operations transforming one string to another.

    editops(source_string, destination_string)
    editops(edit_operations, source_length, destination_length)

    The result is a list of triples (operation, spos, dpos), where
    operation is one of 'equal', 'replace', 'insert', or 'delete';  spos
    and dpos are position of characters in the first (source) and the
    second (destination) strings.  These are operations on single
    characters.  In fact the returned list doesn't contain the 'equal',
    but all the related functions accept both lists with and without
    'equal's.

    Examples
    --------
    >>> editops('spam', 'park')
    [('delete', 0, 0), ('insert', 3, 2), ('replace', 3, 3)]

    The alternate form editops(opcodes, source_string, destination_string)
    can be used for conversion from opcodes (5-tuples) to editops (you can
    pass strings or their lengths, it doesn't matter).
    """
    # convert: we were called (bops, s1, s2)
    if len(args) == 3:
        arg1, arg2, arg3 = args
        len1 = arg2 if isinstance(arg2, int) else len(arg2)
        len2 = arg3 if isinstance(arg3, int) else len(arg3)
        return _Editops(arg1, len1, len2).as_list()

    # find editops: we were called (s1, s2)
    arg1, arg2 = args
    return _Levenshtein.editops(arg1, arg2).as_list()


def opcodes(*args):
    """
    Find sequence of edit operations transforming one string to another.

    opcodes(source_string, destination_string)
    opcodes(edit_operations, source_length, destination_length)

    The result is a list of 5-tuples with the same meaning as in
    SequenceMatcher's get_opcodes() output.  But since the algorithms
    differ, the actual sequences from Levenshtein and SequenceMatcher
    may differ too.

    Examples
    --------
    >>> for x in opcodes('spam', 'park'):
    ...     print(x)
    ...
    ('delete', 0, 1, 0, 0)
    ('equal', 1, 3, 0, 2)
    ('insert', 3, 3, 2, 3)
    ('replace', 3, 4, 3, 4)

    The alternate form opcodes(editops, source_string, destination_string)
    can be used for conversion from editops (triples) to opcodes (you can
    pass strings or their lengths, it doesn't matter).
    """
    # convert: we were called (ops, s1, s2)
    if len(args) == 3:
        arg1, arg2, arg3 = args
        len1 = arg2 if isinstance(arg2, int) else len(arg2)
        len2 = arg3 if isinstance(arg3, int) else len(arg3)
        return _Opcodes(arg1, len1, len2).as_list()

    # find editops: we were called (s1, s2)
    arg1, arg2 = args
    return _Levenshtein.opcodes(arg1, arg2).as_list()


def matching_blocks(edit_operations, source_string, destination_string):
    """
    Find identical blocks in two strings.

    Parameters
    ----------
    edit_operations : list[]
        editops or opcodes created for the source and destination string
    source_string : str | int
        source string or the length of the source string
    destination_string : str | int
        destination string or the length of the destination string

    Returns
    -------
    matching_blocks : list[]
        List of triples with the same meaning as in SequenceMatcher's
        get_matching_blocks() output.

    Examples
    --------
    >>> a, b = 'spam', 'park'
    >>> matching_blocks(editops(a, b), a, b)
    [(1, 0, 2), (4, 4, 0)]
    >>> matching_blocks(editops(a, b), len(a), len(b))
    [(1, 0, 2), (4, 4, 0)]

    The last zero-length block is not an error, but it's there for
    compatibility with difflib which always emits it.

    One can join the matching blocks to get two identical strings:

    >>> a, b = 'dog kennels', 'mattresses'
    >>> mb = matching_blocks(editops(a,b), a, b)
    >>> ''.join([a[x[0]:x[0]+x[2]] for x in mb])
    'ees'
    >>> ''.join([b[x[1]:x[1]+x[2]] for x in mb])
    'ees'
    """
    len1 = source_string if isinstance(source_string, int) else len(source_string)
    len2 = destination_string if isinstance(destination_string, int) else len(destination_string)

    if not edit_operations or len(edit_operations[0]) == 3:
        return _Editops(edit_operations, len1, len2).as_matching_blocks()

    return _Opcodes(edit_operations, len1, len2).as_matching_blocks()


def apply_edit(edit_operations, source_string, destination_string):
    """
    Apply a sequence of edit operations to a string.

    apply_edit(edit_operations, source_string, destination_string)

    In the case of editops, the sequence can be arbitrary ordered subset
    of the edit sequence transforming source_string to destination_string.

    Examples
    --------
    >>> e = editops('man', 'scotsman')
    >>> apply_edit(e, 'man', 'scotsman')
    'scotsman'
    >>> apply_edit(e[:3], 'man', 'scotsman')
    'scoman'

    The other form of edit operations, opcodes, is not very suitable for
    such a tricks, because it has to always span over complete strings,
    subsets can be created by carefully replacing blocks with 'equal'
    blocks, or by enlarging 'equal' block at the expense of other blocks
    and adjusting the other blocks accordingly.

    >>> a, b = 'spam and eggs', 'foo and bar'
    >>> e = opcodes(a, b)
    >>> apply_edit(inverse(e), b, a)
    'spam and eggs'
    """
    if len(edit_operations) == 0:
        return source_string

    len1 = len(source_string)
    len2 = len(destination_string)

    if len(edit_operations[0]) == 3:
        return _Editops(edit_operations, len1, len2).apply(source_string, destination_string)

    return _Opcodes(edit_operations, len1, len2).apply(source_string, destination_string)


def subtract_edit(edit_operations, subsequence):
    """
    Subtract an edit subsequence from a sequence.

    subtract_edit(edit_operations, subsequence)

    The result is equivalent to
    editops(apply_edit(subsequence, s1, s2), s2), except that is
    constructed directly from the edit operations.  That is, if you apply
    it to the result of subsequence application, you get the same final
    string as from application complete edit_operations.  It may be not
    identical, though (in amibuous cases, like insertion of a character
    next to the same character).

    The subtracted subsequence must be an ordered subset of
    edit_operations.

    Note this function does not accept difflib-style opcodes as no one in
    his right mind wants to create subsequences from them.

    Examples
    --------
    >>> e = editops('man', 'scotsman')
    >>> e1 = e[:3]
    >>> bastard = apply_edit(e1, 'man', 'scotsman')
    >>> bastard
    'scoman'
    >>> apply_edit(subtract_edit(e, e1), bastard, 'scotsman')
    'scotsman'
    """
    str_len = 2**32
    return (
        _Editops(edit_operations, str_len, str_len)
        .remove_subsequence(_Editops(subsequence, str_len, str_len))
        .as_list()
    )


def inverse(edit_operations):
    """
    Invert the sense of an edit operation sequence.

    In other words, it returns a list of edit operations transforming the
    second (destination) string to the first (source).  It can be used
    with both editops and opcodes.

    Parameters
    ----------
    edit_operations : list[]
        edit operations to invert

    Returns
    -------
    edit_operations : list[]
        inverted edit operations

    Examples
    --------
    >>> editops('spam', 'park')
    [('delete', 0, 0), ('insert', 3, 2), ('replace', 3, 3)]
    >>> inverse(editops('spam', 'park'))
    [('insert', 0, 0), ('delete', 2, 3), ('replace', 3, 3)]
    """
    if len(edit_operations) == 0:
        return []

    if len(edit_operations[0]) == 3:
        len1 = edit_operations[-1][1] + 1
        len2 = edit_operations[-1][2] + 1
        return _Editops(edit_operations, len1, len2).inverse().as_list()

    len1 = edit_operations[-1][2]
    len2 = edit_operations[-1][4]

    return _Opcodes(edit_operations, len1, len2).inverse().as_list()

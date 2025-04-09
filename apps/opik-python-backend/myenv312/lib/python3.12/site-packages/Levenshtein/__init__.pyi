from __future__ import annotations

from collections.abc import Callable, Hashable, Sequence
from typing import overload

__author__: str
__license__: str
__version__: str

_EditopsList = list[tuple[str, int, int]]
_OpcodesList = list[tuple[str, int, int, int, int]]
_MatchingBlocks = list[tuple[int, int, int]]
_AnyEditops = _EditopsList | _OpcodesList

def inverse(edit_operations: list) -> list: ...
@overload
def editops(s1: Sequence[Hashable], s2: Sequence[Hashable]) -> _EditopsList: ...
@overload
def editops(
    ops: _AnyEditops,
    s1: Sequence[Hashable] | int,
    s2: Sequence[Hashable] | int,
) -> _EditopsList: ...
@overload
def opcodes(s1: Sequence[Hashable], s2: Sequence[Hashable]) -> _OpcodesList: ...
@overload
def opcodes(
    ops: _AnyEditops,
    s1: Sequence[Hashable] | int,
    s2: Sequence[Hashable] | int,
) -> _OpcodesList: ...
def matching_blocks(
    edit_operations: _AnyEditops,
    source_string: Sequence[Hashable] | int,
    destination_string: Sequence[Hashable] | int,
) -> _MatchingBlocks: ...
def subtract_edit(edit_operations: _EditopsList, subsequence: _EditopsList) -> _EditopsList: ...
def apply_edit(edit_operations: _AnyEditops, source_string: str, destination_string: str) -> str: ...
def median(strlist: list[str | bytes], wlist: list[float] | None = None) -> str: ...
def quickmedian(strlist: list[str | bytes], wlist: list[float] | None = None) -> str: ...
def median_improve(
    string: str | bytes,
    strlist: list[str | bytes],
    wlist: list[float] | None = None,
) -> str: ...
def setmedian(strlist: list[str | bytes], wlist: list[float] | None = None) -> str: ...
def setratio(strlist1: list[str | bytes], strlist2: list[str | bytes]) -> float: ...
def seqratio(strlist1: list[str | bytes], strlist2: list[str | bytes]) -> float: ...
def distance(
    s1: Sequence[Hashable],
    s2: Sequence[Hashable],
    *,
    weights: tuple[int, int, int] | None = (1, 1, 1),
    processor: Callable[..., Sequence[Hashable]] | None = None,
    score_cutoff: float | None = None,
    score_hint: float | None = None,
) -> int: ...
def ratio(
    s1: Sequence[Hashable],
    s2: Sequence[Hashable],
    *,
    processor: Callable[..., Sequence[Hashable]] | None = None,
    score_cutoff: float | None = None,
) -> float: ...
def hamming(
    s1: Sequence[Hashable],
    s2: Sequence[Hashable],
    *,
    pad: bool = True,
    processor: Callable[..., Sequence[Hashable]] | None = None,
    score_cutoff: float | None = None,
) -> int: ...
def jaro(
    s1: Sequence[Hashable],
    s2: Sequence[Hashable],
    *,
    processor: Callable[..., Sequence[Hashable]] | None = None,
    score_cutoff: float | None = None,
) -> float: ...
def jaro_winkler(
    s1: Sequence[Hashable],
    s2: Sequence[Hashable],
    *,
    prefix_weight: float | None = 0.1,
    processor: Callable[..., Sequence[Hashable]] | None = None,
    score_cutoff: float | None = None,
) -> float: ...

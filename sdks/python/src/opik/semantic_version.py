# type: ignore

# The content of this module is copied from comet-ml.

import collections
import re
from functools import wraps
from typing import Callable, Dict, List, Optional, Tuple, Union

VersionPart = Union[int, Optional[str]]
VersionTuple = Tuple[
    int,
    int,
    int,
    Optional[Union[str, int]],
    Optional[Union[str, int]],
    Optional[Union[str, int]],
]

ComparableVersion = Union[
    "SemanticVersion", Dict[str, VersionPart], List[VersionPart], VersionTuple, str
]
Comparator = Callable[["SemanticVersion", ComparableVersion], bool]


def _cmp(a, b) -> int:
    return (a > b) - (a < b)


def _comparator(operator: Comparator) -> Comparator:
    @wraps(operator)
    def wrapper(self: "SemanticVersion", other: ComparableVersion) -> bool:
        comparable_types = (
            SemanticVersion,
            dict,
            tuple,
            list,
            str,
        )
        if not isinstance(other, comparable_types):
            return NotImplemented
        return operator(self, other)

    return wrapper


class SemanticVersion:
    # Based on regex from https://semver.org
    # Regex template for a semver version
    _SEMVER_REGEX_TEMPLATE = r"""
                ^
                (?P<major>0|[1-9]\d*)
                (?:\.(?P<minor>0|[1-9]\d*)
                    (?:-(?P<feature_branch>0|[1-9]\d*|[a-zA-Z-_][0-9a-zA-Z-_]*))?
                    (?:\.(?P<patch>0|[1-9]\d*)){opt_patch}
                ){opt_minor}
                (?:-(?P<pre_release>
                    (?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)
                    (?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*
                ))?
                (?:\+(?P<build>
                    [0-9a-zA-Z-]+
                    (?:\.[0-9a-zA-Z-]+)*
                ))?
                $
            """
    # Regex for a semver version
    _SEMVER_REGEX = re.compile(
        _SEMVER_REGEX_TEMPLATE.format(opt_patch="", opt_minor=""),
        re.VERBOSE,
    )
    # Regex for a semver version that might be shorter
    _SEMVER_REGEX_OPTIONAL_MINOR_AND_PATCH = re.compile(
        _SEMVER_REGEX_TEMPLATE.format(opt_patch="?", opt_minor="?"),
        re.VERBOSE,
    )

    def __init__(
        self,
        major: int,
        minor: int = 0,
        patch: int = 0,
        feature_branch: Optional[Union[str, int]] = None,
        pre_release: Optional[Union[str, int]] = None,
        build: Optional[Union[str, int]] = None,
    ):
        self._major = major
        self._minor = minor
        self._patch = patch
        self._feature_branch = None if feature_branch is None else str(feature_branch)
        self._pre_release = None if pre_release is None else str(pre_release)
        self._build = None if build is None else str(build)

    @property
    def major(self) -> int:
        return self._major

    @property
    def minor(self) -> int:
        return self._minor

    @property
    def patch(self) -> int:
        return self._patch

    @property
    def pre_release(self) -> Optional[str]:
        return self._pre_release

    @property
    def build(self) -> Optional[str]:
        return self._build

    @property
    def feature_branch(self) -> Optional[str]:
        return self._feature_branch

    def to_tuple(self) -> VersionTuple:
        return (
            self.major,
            self.minor,
            self.patch,
            self.feature_branch,
            self.pre_release,
            self.build,
        )

    def to_dict(self) -> collections.OrderedDict:
        return collections.OrderedDict(
            (
                ("major", self._major),
                ("minor", self._minor),
                ("feature_branch", self._feature_branch),
                ("patch", self._patch),
                ("pre_release", self._pre_release),
                ("build", self._build),
            )
        )

    def compare(self, other: ComparableVersion) -> int:
        """
        Compare self with other version.

        :param other: another version
        :return: The return value is negative if self < other,
             zero if self == other and strictly positive if self > other
        """
        cls = type(self)
        if isinstance(other, str):
            other = cls.parse(other)
        elif isinstance(other, dict):
            other = cls(**other)
        elif isinstance(other, (tuple, list)):
            other = cls(*other)
        elif not isinstance(other, cls):
            raise TypeError(
                "Wrong type. Expected str, bytes, dict, tuple, list, or %r instance, but got %r"
                % (cls.__name__, type(other))
            )

        v1 = self.to_tuple()[:3]
        v2 = other.to_tuple()[:3]
        return _cmp(v1, v2)

    @_comparator
    def __eq__(self, other: ComparableVersion) -> bool:  # type: ignore
        return self.compare(other) == 0

    @_comparator
    def __ne__(self, other: ComparableVersion) -> bool:  # type: ignore
        return self.compare(other) != 0

    @_comparator
    def __lt__(self, other: ComparableVersion) -> bool:
        return self.compare(other) < 0

    @_comparator
    def __le__(self, other: ComparableVersion) -> bool:
        return self.compare(other) <= 0

    @_comparator
    def __gt__(self, other: ComparableVersion) -> bool:
        return self.compare(other) > 0

    @_comparator
    def __ge__(self, other: ComparableVersion) -> bool:
        return self.compare(other) >= 0

    def __repr__(self) -> str:
        s = ", ".join("%s=%r" % (key, val) for key, val in self.to_dict().items())
        return "%s(%s)" % (type(self).__name__, s)

    def __str__(self) -> str:
        version = "%d.%d" % (self.major, self.minor)
        if self._feature_branch:
            version += "-%s" % self._feature_branch

        version += ".%d" % self.patch

        if self.pre_release:
            version += "-%s" % self.pre_release
        if self.build:
            version += "+%s" % self.build
        return version

    @classmethod
    def parse(
        cls, version: str, optional_minor_and_patch: bool = False
    ) -> "SemanticVersion":
        if not isinstance(version, str):
            raise TypeError("wrong version string type %r" % type(version))

        if optional_minor_and_patch:
            match = cls._SEMVER_REGEX_OPTIONAL_MINOR_AND_PATCH.match(version)
        else:
            match = cls._SEMVER_REGEX.match(version)
        if match is None:
            raise ValueError("%r is not valid SemVer string" % version)

        version_parts = match.groupdict()
        if not version_parts["minor"]:
            version_parts["minor"] = 0
        if not version_parts["patch"]:
            version_parts["patch"] = 0

        major = int(version_parts["major"])
        minor = int(version_parts["minor"])
        patch = int(version_parts["patch"])
        feature_branch = version_parts.get("feature_branch", None)
        pre_release = version_parts.get("pre_release", None)
        build = version_parts.get("build", None)

        return cls(
            major=major,
            minor=minor,
            patch=patch,
            feature_branch=feature_branch,
            pre_release=pre_release,
            build=build,
        )

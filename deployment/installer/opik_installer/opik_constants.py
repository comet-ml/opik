"""Constants for Opik Server."""

from typing import Final, Dict, Any, Union, List

from semver import VersionInfo as semver

from .version import __version__

MINIMUM_PLAYBILL_VERSION: Final[semver] = semver.parse("0.4.0")

ANSI_GREEN: Final[str] = "\033[32m"
ANSI_YELLOW: Final[str] = "\033[33m"
ANSI_RESET: Final[str] = "\033[0m"
UNICODE_BALLOT_BOX_WITH_CHECK: Final[str] = "\u2611"
UNICODE_WARNING_SIGN: Final[str] = "\u26A0"
UNICODE_EMOJI_CLOCK_FACES: Final[str] = "".join([
    "\U0001F55B",  # Clock Face Twelve O'Clock
    "\U0001F567",  # Clock Face Twelve-Thirty
    "\U0001F550",  # Clock Face One O'Clock
    "\U0001F55C",  # Clock Face One-Thirty
    "\U0001F551",  # Clock Face Two O'Clock
    "\U0001F55D",  # Clock Face Two-Thirty
    "\U0001F552",  # Clock Face Three O'Clock
    "\U0001F55E",  # Clock Face Three-Thirty
    "\U0001F553",  # Clock Face Four O'Clock
    "\U0001F55F",  # Clock Face Four-Thirty
    "\U0001F554",  # Clock Face Five O'Clock
    "\U0001F560",  # Clock Face Five-Thirty
    "\U0001F555",  # Clock Face Six O'Clock
    "\U0001F561",  # Clock Face Six-Thirty
    "\U0001F556",  # Clock Face Seven O'Clock
    "\U0001F562",  # Clock Face Seven-Thirty
    "\U0001F557",  # Clock Face Eight O'Clock
    "\U0001F563",  # Clock Face Eight-Thirty
    "\U0001F558",  # Clock Face Nine O'Clock
    "\U0001F564",  # Clock Face Nine-Thirty
    "\U0001F559",  # Clock Face Ten O'Clock
    "\U0001F565",  # Clock Face Ten-Thirty
    "\U0001F55A",  # Clock Face Eleven O'Clock
    "\U0001F566",  # Clock Face Eleven-Thirty
])

DEFAULT_HELM_REPO_NAME: Final[str] = "opik"
DEFAULT_HELM_REPO_URL: Final[str] = "https://comet-ml.github.io/opik"
DEFAULT_HELM_REPO_USERNAME: Final[str] = ""
DEFAULT_HELM_REPO_PASSWORD: Final[str] = ""
DEFAULT_HELM_CHART_NAME: Final[str] = "opik"
DEFAULT_HELM_CHART_VERSION: Final[str] = ""
DEFAULT_CONTAINER_REGISTRY: Final[str] = "ghcr.io"
DEFAULT_CONTAINER_REPO_PREFIX: Final[str] = "comet-ml/opik/opik"
DEFAULT_CONTAINER_REGISTRY_USERNAME: Final[str] = ""
DEFAULT_CONTAINER_REGISTRY_PASSWORD: Final[str] = ""
DEFAULT_OPIK_VERSION: Final[str] = __version__
DEFAULT_ANSIBLE_PATH: Final[str] = ""


REUSABLE_OPT_ARGS: Final[
    Dict[
        str,
        Dict[
            str,
            Union[
                List[Any],
                Dict[str, Any]
            ]
        ]
    ]
] = {
    "helm-repo-name": {
        "args": ["--helm-repo-name"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_REPO_NAME,
            "show_default": True,
            "help": "Helm Repository Name",
        },
    },
    "helm-repo-url": {
        "args": ["--helm-repo-url"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_REPO_URL,
            "show_default": True,
            "help": "Helm Repository URL",
        },
    },
    "helm-repo-username": {
        "args": ["--helm-repo-username"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_REPO_USERNAME,
            "help": "Helm Repository Username",
        },
    },
    "helm-repo-password": {
        "args": ["--helm-repo-password"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_REPO_PASSWORD,
            "help": "Helm Repository Password",
        },
    },
    "helm-chart-name": {
        "args": ["--helm-chart-name"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_CHART_NAME,
            "show_default": True,
            "help": "Helm Chart Name",
        },
    },
    "helm-chart-version": {
        "args": ["--helm-chart-version"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_HELM_CHART_VERSION,
            "show_default": True,
            "help": "Helm Chart Version",
        },
    },
    "container-registry": {
        "args": ["--container-registry"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_CONTAINER_REGISTRY,
            "show_default": True,
            "help": "Container Registry",
        },
    },
    "container-registry-username": {
        "args": ["--container-registry-username"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_CONTAINER_REGISTRY_USERNAME,
            "help": "Container Registry Username",
        },
    },
    "container-registry-password": {
        "args": ["--container-registry-password"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_CONTAINER_REGISTRY_PASSWORD,
            "help": "Container Registry Password",
        },
    },
    "container-repo-prefix": {
        "args": ["--container-repo-prefix"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_CONTAINER_REPO_PREFIX,
            "show_default": True,
            "help": "Container Repository Prefix",
        },
    },
    "opik-version": {
        "args": ["--opik-version"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_OPIK_VERSION,
            "show_default": True,
            "help": "Version of Opik to install",
        },
    },
    "local-port": {
        "args": ["--local-port"],
        "kwargs": {
            "required": False,
            "default": 5173,
            "type": int,
            "show_default": True,
            "help": "Local port to expose Opik on",
        },
    },
    "ansible-path": {
        "args": ["--ansible-path"],
        "kwargs": {
            "required": False,
            "default": DEFAULT_ANSIBLE_PATH,
            "help": "Path to the ansible-playbook binary",
        },
    },
}

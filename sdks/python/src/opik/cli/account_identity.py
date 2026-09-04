"""Who ran a configuration command, for the `configuration` analytics events.

`opik configure` and `opik mcp configure` are where MCP adoption starts, and their
events carried nothing the MCP funnels key on - so a configure run could not be tied
to the person who went on to use the MCP server, or to drop it. One runner in twenty
was attributable.

Three keys are reported, because no single one covers the population. Measured over
30 days against the local stdio funnel's own grain, 761 installs:

- `api_key_sha256` - **628 installs (83%)**. The MCP server reports the digest of the
  same key this command configures, so two runs of the same credential meet here even
  when nobody's name could be resolved on either side. The widest bridge by far, and
  the only one available on the first run of a brand-new install.
- `user_id` - the Comet login, **72 installs (9%)**, a strict subset of the above. Thin
  on its own, and worth reporting anyway: it is the warehouse's own user key and the
  only key that names a person rather than a credential.
- `install_id` - the MCP server's own per-machine id, read from `~/.opik-mcp/install-id`
  when it is already there. Exact, and the only bridge for the 133 installs (17%) with
  no credential at all - a local or open source Opik, which has no accounts. Read,
  never written: the server reports `install_id_freshly_generated` on the run that
  creates that file, and creating it here would report every onboarding as a returning
  install.

The login is a plaintext personal identifier, which is a narrow, deliberate amendment
to the analytics privacy contract in `opik.analytics`: hashing it would make it
unjoinable, which is the only reason to report it. The key is reported only as a
one-way digest; the key itself is never sent anywhere but the Opik deployment it
authenticates against. These two commands are the only place either is reported, and
nothing else personal goes with them.

Three rules, so that none of this is ever something a user feels:

- **Nothing is looked up when nothing would be reported.** This is work done for
  analytics, so `OPIK_ANALYTICS_ENABLE=false` switches it off too.
- **Cloud only, and bounded.** `account-details` does not exist on a self-hosted or
  local Opik, and no configure run should spend a timeout on an answer that cannot
  exist there.
- **Failure is silent, and countable.** Every path reports `identity_lookup`, so an
  unattributed run says which reason it was rather than being an absence someone has
  to guess at.

`identity_lookup`, `workspace` and `workspace_kind` deliberately reuse the MCP
server's own property names and values - `resolved` / `miss` / `none_expected`, and
`configured` / `resolved` / `placeholder` / `unknown` - so one BI query reads both
products. `no_credential` is the one value the MCP has no analogue for: it is a run
that has not reached its credential yet, rather than one that will never have it.

Note what this does NOT change: the identity an event is *attributed* to. Every SDK
event, these included, is still keyed on the workspace-derived `anonymous_id`, while
the MCP server keys its events on the login. So the login reported here joins the two
by value - which is what the warehouse joins on - and not as one PostHog person.
"""

import dataclasses
import hashlib
import logging
import pathlib
import uuid
from typing import Any, Dict, Optional, Tuple

import httpx

import opik.config as opik_config
import opik.url_helpers as url_helpers
from opik import analytics

LOGGER = logging.getLogger(__name__)

# Where the MCP server keeps its per-machine id. Matches
# `opik_mcp.analytics.identity._install_id_path`; a different path here would report
# an id nothing else has ever seen.
_MCP_INSTALL_ID_PATH = pathlib.Path.home() / ".opik-mcp" / "install-id"

# Tight on purpose: this runs inside a command someone is waiting on. The answer is
# worth a moment and never a hang, and it is cached below, so a command pays for it
# at most once however many events it reports.
_TIMEOUT_SECONDS = 3.0

# A CLI run configures one account, so this exists to serve the second event of a
# pair rather than to hold a directory. Bounded so nothing accumulates in a process
# that calls `opik.configure()` in a loop.
_MAX_CACHED_ACCOUNTS = 4


@dataclasses.dataclass(frozen=True)
class _Account:
    """What `account-details` says about the holder of an API key."""

    user_name: Optional[str]
    default_workspace_name: Optional[str]


# Keyed by `(key digest, base url)`: recognising a key we already resolved needs no
# more than its digest, so the process never holds a credential to do it.
_RESOLVED: Dict[Tuple[str, str], Optional[_Account]] = {}


def event_properties() -> Dict[str, analytics.PropertyValue]:
    """The identity properties to report with a `configuration` event.

    `identity_lookup` and `workspace_kind` are always present: they say where the
    other two values came from, which is what makes a missing one countable instead
    of indistinguishable from a version that never reported it.

    Never raises, and returns nothing at all when analytics is switched off.
    """
    try:
        if not analytics.reporting_allowed():
            return {}

        return _properties(opik_config.OpikConfig())
    except Exception:
        LOGGER.debug("Failed to resolve the account to report", exc_info=True)
        return {"identity_lookup": "miss", "workspace_kind": "unknown"}


def _properties(
    config_: opik_config.OpikConfig,
) -> Dict[str, analytics.PropertyValue]:
    account, lookup = _resolve(config_)
    workspace, workspace_kind = _workspace(config_, account)

    properties: Dict[str, analytics.PropertyValue] = {
        "identity_lookup": lookup,
        "workspace_kind": workspace_kind,
    }
    if account is not None and account.user_name:
        properties["user_id"] = account.user_name
    if workspace is not None:
        properties["workspace"] = workspace
    if config_.api_key:
        # Reported whatever the deployment: a self-hosted install resolves no login,
        # but its key still meets the MCP server's digest of the same key.
        properties["api_key_sha256"] = _digest(config_.api_key)

    install_id = _mcp_install_id()
    if install_id is not None:
        properties["install_id"] = install_id

    return properties


def _resolve(config_: opik_config.OpikConfig) -> Tuple[Optional[_Account], str]:
    """The account behind this run, and why it came out that way."""
    # Asked before the credential, because the two answers mean opposite things: a
    # missing key is a run that has not got there yet, while a deployment with no
    # accounts is one that can never be attributed at all.
    if not config_.is_cloud_installation:
        # No account-details endpoint on a self-hosted Opik, and no accounts at all
        # on the open source one. Unattributable by construction, not a gap to close
        # - which is what the MCP server means by this value too.
        return None, "none_expected"

    if not config_.api_key:
        # `opik configure` reports its first event before it has asked for a key, so
        # this is the ordinary state of a first-ever run rather than a failure.
        return None, "no_credential"

    account = _fetch(config_.api_key, url_helpers.get_base_url(config_.url_override))
    if account is None or not account.user_name:
        return account, "miss"

    return account, "resolved"


def _workspace(
    config_: opik_config.OpikConfig, account: Optional[_Account]
) -> Tuple[Optional[str], str]:
    """The workspace to report, and where its name came from.

    Reported explicitly even though every event is already attributed to a
    workspace: that attribution is read once per process, and `opik configure`
    changes the workspace while it runs - so on the run that matters most it names
    the workspace the user had before this command, or the shared `default`
    sentinel. This is the one the command actually configured.
    """
    configured = (config_.workspace or "").strip()

    if configured and configured != opik_config.OPIK_WORKSPACE_DEFAULT_NAME:
        # Someone named this workspace deliberately, and they may be working outside
        # their account default - so it outranks the resolved name.
        return configured, "configured"

    resolved = account.default_workspace_name if account is not None else None
    if resolved:
        return resolved, "resolved"

    if configured:
        # The literal `default`: one name shared by every install that never set
        # one, so it is reported but must never be joined on as a workspace.
        return configured, "placeholder"

    return None, "unknown"


def _fetch(api_key: str, base_url: str) -> Optional[_Account]:
    """Ask Comet who holds this key. `None` on any failure whatsoever.

    Cached for the process: a command reports an entry event and a result event, and
    the second must not cost a second round-trip. The cache is keyed by a digest of
    the key rather than the key itself, and bounded, so a long-lived process that
    configures several accounts never accumulates credentials in memory - it holds
    only what it needs to recognise a key it has already resolved.

    TLS is always verified, matching the analytics sender: `check_tls_certificate`
    exists for a self-hosted deployment's own certificate, and this only ever calls
    Comet's cloud host.
    """
    cache_key = (_digest(api_key), base_url)
    if cache_key in _RESOLVED:
        return _RESOLVED[cache_key]

    account = _request(api_key, base_url)

    # A plain dict with a bound rather than an LRU: one CLI run resolves one
    # account, so eviction order cannot matter, and dropping the oldest keeps the
    # common case (one entry) allocation-free.
    if len(_RESOLVED) >= _MAX_CACHED_ACCOUNTS:
        _RESOLVED.pop(next(iter(_RESOLVED)))
    _RESOLVED[cache_key] = account

    return account


def _request(api_key: str, base_url: str) -> Optional[_Account]:
    try:
        with httpx.Client(timeout=_TIMEOUT_SECONDS, verify=True) as client:
            response = client.get(
                url_helpers.get_account_details_url(base_url),
                headers={"Authorization": api_key},
            )

        if response.status_code != 200:
            LOGGER.debug("account-details returned %s", response.status_code)
            return None

        body = response.json()
    except Exception:
        LOGGER.debug("account-details lookup failed", exc_info=True)
        return None

    if not isinstance(body, dict):
        return None

    return _Account(
        user_name=_text(body.get("userName")),
        default_workspace_name=_text(body.get("defaultWorkspaceName")),
    )


def _digest(api_key: str) -> str:
    """SHA-256 hex of the key, the transform `opik_mcp.credential_identity` uses.

    Lowercase hex of the UTF-8 bytes, because BI joins on the exact string: a
    different encoding or casing here would produce a digest that matches nothing.

    A plain fast hash is the right primitive, and CodeQL's
    `py/weak-sensitive-data-hashing` will disagree because the input is named like
    a credential. That rule is about *storing a password for verification*, where
    a fast hash lets whoever steals the store brute-force human-chosen secrets.
    Neither half holds here:

    - This is a label, not a verifier. Nothing compares it to anything to grant
      access; it exists so two reports of the same credential can be recognised as
      one setup. Only the digest is ever reported - the key itself goes nowhere
      except the Opik deployment it authenticates against, which is where the
      configure flow already sends it.
    - A password KDF cannot do the job. bcrypt / scrypt / PBKDF2 are salted, so the
      same key would digest differently on every machine and join to nothing - and
      an unsalted slow hash would still have to match what the MCP server emits,
      which is this.

    An Opik API key is also a high-entropy generated value rather than something a
    person chose, so the guessing attack the rule guards against does not apply.
    """
    return hashlib.sha256(api_key.encode("utf-8")).hexdigest()


def _mcp_install_id() -> Optional[str]:
    """The MCP server's per-machine id if it has already written one.

    Deliberately read-only, and deliberately not created. The server reports
    `install_id_freshly_generated` on whichever run writes that file, and the install
    funnel counts new installs with it - so writing it from here would file every
    person who onboards through this command as a returning install instead.

    Normalised through `UUID` exactly as the server does, so a hand-edited or
    truncated file reports nothing rather than an id that joins to nothing.
    """
    try:
        return str(uuid.UUID(_MCP_INSTALL_ID_PATH.read_text().strip()))
    except Exception:
        LOGGER.debug("No MCP install id to report", exc_info=True)
        return None


def _text(value: Any) -> Optional[str]:
    if not isinstance(value, str) or not value.strip():
        return None

    return value.strip()

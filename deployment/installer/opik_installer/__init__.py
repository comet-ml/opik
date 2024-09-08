"""opik_installer - Command Line Interface for installing Opik."""

import getpass
import os
import sys
import subprocess
import threading
import time
import traceback

from functools import update_wrapper
from typing import Callable, Tuple, cast, Dict, Optional
from importlib import metadata

import click

from semver import VersionInfo as semver

import opik_installer.opik_constants as c
from .version import __version__

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}

__called_with_debug: bool = False

AnsibleRunner = None  # pylint: disable=invalid-name
PlaybookConfig = None  # pylint: disable=invalid-name


def debug_set() -> bool:
    """Return the debug flag.

    Must be called within a Click command.
    """
    ctx = click.get_current_context()
    while ctx.params.get("debug") is None and (
        ctx_parent := ctx.parent
    ) is not None:
        ctx = ctx_parent
    debug: bool = ctx.params.get("debug", False)
    return debug


def run_external_subroutine(
    msg: str,
    func: Callable,
    args: Tuple,
    failure_sentinel: threading.Event,
    debug: bool = False,
) -> None:
    """run_external_subroutine.

    Shows a pretty text animation while doing some processing.
    Or just runs the function without the spinner if debug is True.

    Args:
        msg (str): The message to show before the spinner.
        func (Callable): The function to run while showing the spinner.
        args (Tuple): Arguments to the function to call.
        failure_sentinel (threading.Event): An event that will be set from
            within the provided function if it fails.
        debug (bool): Whether to run the function without the spinner.
    """
    if sys.stdout.encoding.startswith('utf'):
        spinner = c.UNICODE_EMOJI_CLOCK_FACES
        success_check = c.UNICODE_BALLOT_BOX_WITH_CHECK
        failure_warning = c.UNICODE_WARNING_SIGN
    else:
        spinner = "|/-\\"
        success_check = "[OK]"
        failure_warning = "/!\\"
    if debug:
        func(*args)
        return
    failure_sentinel.clear()
    spinnered_thread = threading.Thread(target=func, args=args)
    spinnered_thread.start()
    while True:
        for char in spinner:
            if not spinnered_thread.is_alive():
                char = c.ANSI_GREEN + success_check + c.ANSI_RESET
                if failure_sentinel.is_set():
                    char = c.ANSI_YELLOW + failure_warning + c.ANSI_RESET
            print(f"\r{msg}... {char}", end="", flush=True)
            if not spinnered_thread.is_alive():
                print()
                return
            time.sleep(1/12)


def require_playbill(
    func: Callable[..., None]
) -> Callable[..., None]:  # noqa: D202, D301
    """require_playbill decorator.

    If a Click command requires anisble-playbill, this decorator will
    ensure that it is available.
    \f
    Args:
        f (Callable[..., None]): Function to be decorated.

    Returns:
        Callable[..., None]: Transparently decorated function.
    """

    # Documented technique for adding a decorator to a click command:
    # https://web.archive.org/web/20230302175114/https://click.palletsprojects.com/en/8.1.x/commands/#decorating-commands
    @click.pass_context
    def decorator(ctx: click.Context, *args: Tuple, **kwargs: Dict) -> None:
        global AnsibleRunner, PlaybookConfig  # pylint: disable=global-statement,invalid-name # noqa: E501
        try:
            from ansible_playbill import __package__ as playbill_pkg  # noqa: F401,E501 # pylint: disable=unused-import,import-outside-toplevel
            playbill_version = metadata.version(playbill_pkg)
            try:
                if debug_set():
                    click.echo(f"Playbill Version: {playbill_version}")
                playbill_semver = semver.parse(playbill_version)
                if playbill_semver < c.MINIMUM_PLAYBILL_VERSION:
                    if not str(playbill_semver.build).startswith("dev"):
                        raise ImportError
            except ValueError:
                pass
        except ImportError:
            subprocess.run(
                [
                    sys.executable,
                    "-m", "pip",
                    "install",
                    "--upgrade",
                    "ansible-playbill",
                ],
                check=True,
            )
        finally:
            from ansible_playbill import AnsibleRunner as ar  # noqa: F401,E501 # pylint: disable=unused-import,import-outside-toplevel
            from ansible_playbill import PlaybookConfig as pc  # noqa: F401,E501 # pylint: disable=unused-import,import-outside-toplevel
            AnsibleRunner = ar
            PlaybookConfig = pc

        closure: None = ctx.invoke(func, *args, **kwargs)
        return closure

    return update_wrapper(decorator, func)


@click.group(invoke_without_command=True, context_settings=CONTEXT_SETTINGS)
@click.version_option(__version__, *("--version", "-v"))
@click.option(
    "--debug",
    required=False,
    is_flag=True,
    default=False,
    help="Run in debug mode",
)
@click.pass_context
def opik_server(ctx: click.Context, debug: bool) -> None:  # noqa: D301
    """Utility for installing and administering Self-Hosted Opik.
    \f
    Base group for the opik-server utility.

    Args:
        ctx (click.Context): ctx

    Returns:
        None:
    """
    if debug:
        global __called_with_debug  # pylint: disable=global-statement,invalid-name # noqa: E501
        __called_with_debug = True
        ctx.obj = (ctx.obj or {}).update({"debug": debug})
    if ctx.invoked_subcommand is None:
        click.echo(ctx.get_help(), color=ctx.color)
        ctx.exit()


@opik_server.command()
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-name"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-name"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-url"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-url"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-username"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-username"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-password"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-password"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-chart-name"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-chart-name"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-chart-version"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-chart-version"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry-username"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry-username"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry-password"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry-password"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-repo-prefix"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-repo-prefix"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["opik-version"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["opik-version"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["ansible-path"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["ansible-path"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["local-port"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["local-port"]["kwargs"]),
)
@click.option(
    "--no-deps",
    required=False,
    is_flag=True,
    help="Skip installation of dependencies",
)
@require_playbill
def install(  # noqa: C901
    helm_repo_name: str,
    helm_repo_url: str,
    helm_repo_username: str,
    helm_repo_password: str,
    helm_chart_name: str,
    helm_chart_version: str,
    container_registry: str,
    container_registry_username: str,
    container_registry_password: str,
    container_repo_prefix: str,
    opik_version: str,
    ansible_path: str,
    local_port: int,
    no_deps: bool,
    upgrade_called: bool = False,
) -> None:  # noqa: D301
    """Install Self-Hosted Opik Server.
    \f
    Args:
        helm_repo_name (str): Helm Repository Name
        helm_repo_url (str): Helm Repository URL
        helm_repo_username (str): Helm Repository Username
        helm_repo_password (str): Helm Repository Password
        helm_chart_name (str): Helm Chart Name
        helm_chart_version (str): Helm Chart Version
        container_registry (str): Container Registry
        container_registry_username (str): Container Registry Username
        container_registry_password (str): Container Registry Password
        container_repo_prefix (str): Container Repository Prefix
        opik_version (str): Version of Opik to install
        ansible_path (str): Path to the ansible-playbook executable
        local_port (int): Local port to run the Opik server on
        no_deps (bool): Skip installation of dependencies

    Returns:
        None:
    """
    debug: bool = debug_set()
    if debug:
        click.echo("Debug mode is enabled.")

    # Determine the need for privilege escalation.
    become_pass: str = ""
    if os.geteuid() == 0:
        click.echo(
            "Opik should be installed as a non-root user with sudo privileges",
            err=True,
        )
        sys.exit(1)
    else:
        # If we're not root, check if we can become root without a password.
        try:
            subprocess.run(
                ['sudo', '-n', '-k', 'true'],
                check=True,
                capture_output=True,
            )
        except subprocess.CalledProcessError:
            # If we can't become root without a password, prompt for it.
            attempt_counter: int = 0
            while not become_pass:
                if attempt_counter > 2:
                    click.echo(
                        "Too many failed attempts. Exiting...",
                        err=True,
                    )
                    sys.exit(1)
                become_pass = click.prompt(
                    "Installation requires root permissions.\n"
                    "Please provide your sudo/administrator password",
                    hide_input=True
                )
                attempt_counter += 1

                # Test the provided password.
                res = subprocess.run(  # pylint: disable=subprocess-run-check
                    ['sudo', '-S', '-k', 'true'],
                    input=f"{become_pass}\n",
                    capture_output=True,
                    text=True,
                )
                if res.returncode != 0:
                    click.echo("Invalid sudo/admin password", err=True)
                    become_pass = ""

    # Sanitize the ansible path if provided, or default to the system path.
    if not ansible_path:
        ansible_path = str(sys.path[0])
    elif os.path.basename(ansible_path) in ["ansible", "ansible-playbook"]:
        ansible_path = os.path.dirname(ansible_path)

    # Check if the ansible-playbook executable exists and is executable.
    if not os.path.exists(os.path.join(ansible_path, "ansible-playbook")):
        click.echo(
            "The ansible-playbook executable path does not exist.",
            err=True,
        )
        sys.exit(1)
    elif not os.access(
        os.path.join(ansible_path, "ansible-playbook"),
        os.X_OK,
    ):
        click.echo(
            "The ansible-playbook executable path is not executable.",
            err=True,
        )
        sys.exit(1)

    # If the prior tests yielded a password, prepare to pass it to the Ansible
    # runner.
    global_vars: dict = {
        "ansible_connection_user": getpass.getuser(),
        "helm_repo_name": helm_repo_name,
        "helm_repo_url": helm_repo_url,
        "helm_repo_username": helm_repo_username,
        "helm_repo_password": helm_repo_password,
        "helm_chart_name": helm_chart_name,
        "helm_chart_version": helm_chart_version,
        "container_registry": container_registry,
        "container_registry_username": container_registry_username,
        "container_registry_password": container_registry_password,
        "container_repo_prefix": container_repo_prefix,
        "comet_opik_version": opik_version,
        "opik_local_port": local_port,
    }
    if become_pass:
        global_vars.update({"ansible_become_password": become_pass})

    playbook_name: str = "opik-deps"
    if no_deps:
        playbook_name = "opik"

    failure_sentinel = threading.Event()

    def run_all():
        try:
            AnsibleRunner(
                playbook_root=os.path.join(
                    str(metadata.distribution(__package__).locate_file('')),
                    __package__,
                    'ansible',
                ),
                global_vars=global_vars,
                playbooks=[
                    PlaybookConfig(
                        f"play.{playbook_name}.yml",
                    ),
                ],
                debug=debug,
                ansible_bin_path=ansible_path,
            ).run_all()
        except Exception as ex:  # pylint: disable=broad-except
            failure_sentinel.set()
            if debug:
                raise ex

    click.echo()
    run_external_subroutine(
        "Installing Latest Opik Server",
        run_all,
        (),
        failure_sentinel,
        debug=debug,
    )
    if failure_sentinel.is_set():
        click.echo("Installation failed.", err=True)
        sys.exit(1)

    click.echo("Latest Opik Installation complete!")
    click.echo()
    if not upgrade_called:
        click.echo(
            "You can access Opik at: "
            f"http://localhost:{local_port} in your browser."
        )


@opik_server.command()
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-name"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-name"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-url"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-url"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-username"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-username"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-repo-password"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-repo-password"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-chart-name"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-chart-name"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["helm-chart-version"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["helm-chart-version"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry-username"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry-username"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-registry-password"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-registry-password"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["container-repo-prefix"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["container-repo-prefix"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["opik-version"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["opik-version"]["kwargs"]),
)
@click.option(
    *c.REUSABLE_OPT_ARGS["ansible-path"]["args"],
    **cast(dict, c.REUSABLE_OPT_ARGS["ansible-path"]["kwargs"]),
)
@click.pass_context
def upgrade(
    ctx: click.Context,
    helm_repo_name: str,
    helm_repo_url: str,
    helm_repo_username: str,
    helm_repo_password: str,
    helm_chart_name: str,
    helm_chart_version: str,
    container_registry: str,
    container_registry_username: str,
    container_registry_password: str,
    container_repo_prefix: str,
    opik_version: str,
    ansible_path: str,
) -> None:  # noqa: D301
    """Upgrade Self-Hosted Opik Server.
    \f
    Args:
        ctx (click.Context): ctx
        helm_repo_name (str): Helm Repository Name
        helm_repo_url (str): Helm Repository URL
        helm_repo_username (str): Helm Repository Username
        helm_repo_password (str): Helm Repository Password
        helm_chart_name (str): Helm Chart Name
        helm_chart_version (str): Helm Chart Version
        container_registry (str): Container Registry
        container_registry_username (str): Container Registry Username
        container_registry_password (str): Container Registry Password
        container_repo_prefix (str): Container Repository Prefix
        opik_version (str): Version of Opik to install
        ansible_path (str): Path to the ansible-playbook executable

    Returns:
        None:
    """
    ctx.invoke(
        install,
        helm_repo_name=helm_repo_name,
        helm_repo_url=helm_repo_url,
        helm_repo_username=helm_repo_username,
        helm_repo_password=helm_repo_password,
        helm_chart_name=helm_chart_name,
        helm_chart_version=helm_chart_version,
        container_registry=container_registry,
        container_registry_username=container_registry_username,
        container_registry_password=container_registry_password,
        container_repo_prefix=container_repo_prefix,
        opik_version=opik_version,
        ansible_path=ansible_path,
        no_deps=True,
        upgrade_called=True,
    )


def main():
    """Main entry point for opik-server."""
    try:
        opik_server(prog_name=__package__)  # pylint: disable=no-value-for-parameter # noqa: E501
    except Exception as ex:  # pylint: disable=broad-except
        if __called_with_debug:
            click.echo(f"Unexpected Error: {ex}", err=True)
            click.echo(
                ''.join(traceback.format_tb(ex.__traceback__)),
                err=True,
            )
            sys.exit(1)
        click.echo(f"Unexpected Error: {ex}", err=True)
    sys.exit(1)


if __name__ == "__main__":
    main()

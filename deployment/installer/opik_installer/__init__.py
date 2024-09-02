"""opik_installer - Command Line Interface for installing Opik."""

import getpass
import os
import sys
import subprocess
import threading
import time
import traceback

from typing import Callable, Tuple, Final
from importlib import metadata

import click

from ansible_playbill import AnsibleRunner, PlaybookConfig

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

__version__: str = "0.0.0+dev"
if __package__:
    __version__ = metadata.version(__package__)

CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}

__called_with_debug: bool = False


def debug_set() -> bool:
    """Return the debug flag.

    Must be called within a Click command.
    """
    debug: bool = click.get_current_context().find_root().params.get(
        "debug", False,
    )
    return debug


def run_external_subroutine(
    msg: str,
    func: Callable,
    args: Tuple,
    failure_sentinel: threading.Event,
    debug: bool = False,
) -> None:
    """run_external_subroutine.

    Shows a pretty ascii animation while doing some processing.

    Args:
        msg (str): The message to show before the spinner.
        func (Callable): The function to run while showing the spinner.
        args (Tuple): Arguments to the function to call.
        failure_sentinel (threading.Event): An event that will be set from
            within the provided function if it fails.
        debug (bool): Whether to run the function without the spinner.
    """
    if sys.stdout.encoding.startswith('utf'):
        spinner = UNICODE_EMOJI_CLOCK_FACES
        success_check = UNICODE_BALLOT_BOX_WITH_CHECK
        failure_warning = UNICODE_WARNING_SIGN
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
                char = ANSI_GREEN + success_check + ANSI_RESET
                if failure_sentinel.is_set():
                    char = ANSI_YELLOW + failure_warning + ANSI_RESET
            print(f"\r{msg}... {char}", end="", flush=True)
            if not spinnered_thread.is_alive():
                print()
                return
            time.sleep(1/12)


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
    "--helm-repo-name",
    required=False,
    default="opik",
    show_default=True,
    help="Helm Repository Name",
)
@click.option(
    "--helm-repo-url",
    required=False,
    default="https://opik.github.io/helm-charts",
    show_default=True,
    help="Helm Repository URL",
)
@click.option(
    "--helm-repo-username",
    required=False,
    default="",
    help="Helm Repository Username",
)
@click.option(
    "--helm-repo-password",
    required=False,
    default="",
    help="Helm Repository Password",
)
@click.option(
    "--helm-chart-name",
    required=False,
    default="opik",
    show_default=True,
    help="Helm Chart Name",
)
@click.option(
    "--helm-chart-version",
    required=False,
    default="",
    show_default=True,
    help="Helm Chart Version",
)
@click.option(
    "--container-registry",
    required=False,
    default="ghcr.io",
    show_default=True,
    help="Container Registry",
)
@click.option(
    "--container-registry-username",
    required=False,
    default="",
    help="Container Registry Username",
)
@click.option(
    "--container-registry-password",
    required=False,
    default="",
    help="Container Registry Password",
)
@click.option(
    "--container-repo-prefix",
    required=False,
    default="opik/opik",
    show_default=True,
    help="Container Repository Prefix",
)
@click.option(
    "--opik-version",
    required=False,
    default=__version__,
    show_default=True,
    help="Version of Opik to install",
)
@click.option(
    "--ansible-path",
    required=False,
    default="",
    help="Path to the ansible-playbook binary",
)
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
) -> None:  # noqa: D301
    """Install Self-Hosted Opik Server.
    \f
    Returns:
        None:
    """
    click.echo("Installing Local Opik Server...")
    debug: bool = debug_set()
    if debug:
        click.echo("Debug mode is enabled.")

    # Determine the need for privilege escalation.
    become_pass: str = ""
    if os.geteuid() != 0:
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
    }
    if become_pass:
        global_vars.update({"ansible_become_password": become_pass})

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
                        "play.opik.yml",
                    ),
                ],
                debug=debug,
                ansible_bin_path=ansible_path,
            ).run_all()
        except Exception:  # pylint: disable=broad-except
            failure_sentinel.set()

    click.echo()
    run_external_subroutine(
        "Installing Opik Server",
        run_all,
        (),
        failure_sentinel,
        debug=debug,
    )
    if failure_sentinel.is_set():
        click.echo("Installation failed.", err=True)
        sys.exit(1)

    click.echo("Installation complete!")
    click.echo()
    click.echo(
        "You can access Opik at: http://localhost:5173 in your browser."
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

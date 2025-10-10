"""Proxy command for Opik CLI."""

import sys

import click


@click.command(context_settings={"ignore_unknown_options": True})
@click.option(
    "--ollama",
    is_flag=True,
    help="Run as a proxy server for Ollama",
)
@click.option(
    "--ollama-host",
    default="http://localhost:11434",
    help="Ollama server URL when using --ollama-proxy",
    show_default=True,
)
@click.option(
    "--lm-studio",
    is_flag=True,
    help="Run as a proxy server for LM Studio",
)
@click.option(
    "--lm-studio-host",
    default="http://localhost:1234",
    help="LM Studio server URL when using --lm-studio-proxy",
    show_default=True,
)
@click.option(
    "--host",
    default="localhost",
    help="Host to bind to",
    show_default=True,
)
@click.option(
    "--port",
    default=7860,
    help="Port to bind to",
    show_default=True,
)
def proxy(
    ollama: bool,
    ollama_host: str,
    lm_studio: bool,
    lm_studio_host: str,
    host: str,
    port: int,
) -> None:
    """Start the Opik server."""
    try:
        import fastapi  # noqa
        import uvicorn  # noqa
        import httpx  # noqa
        import rich  # noqa
    except ImportError:
        raise click.ClickException(
            "Proxy server dependencies not found. Please install them with: pip install opik[proxy]"
        )

    if not ollama and not lm_studio:
        click.echo(
            "Error: Either --ollama or --lm-studio must be specified",
            err=True,
        )
        sys.exit(1)

    if ollama and lm_studio:
        click.echo("Error: Cannot specify both --ollama and --lm-studio", err=True)
        sys.exit(1)

    if ollama:
        llm_server_host = ollama_host
        llm_server_type = "Ollama"
    else:  # lm_studio_proxy
        llm_server_host = lm_studio_host
        llm_server_type = "LM Studio"

    from opik.forwarding_server.app import create_app
    from opik.forwarding_server.utils import print_server_startup_message
    import uvicorn

    app = create_app(llm_server_host)
    print_server_startup_message(
        host=host,
        port=port,
        llm_server_type=llm_server_type,
        llm_server_host=llm_server_host,
    )
    uvicorn.run(
        app, host=host, port=port, log_level="error"
    )  # Reduce uvicorn logging to keep output clean

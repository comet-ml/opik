import logging
from contextlib import contextmanager
from typing import Dict, List, Optional

from rich import box
from rich.console import Console, Group
from rich.panel import Panel
from rich.progress import track
from rich.text import Text

from .utils import get_optimization_run_url_by_id

PANEL_WIDTH = 70

def get_console(*args, **kwargs):
    console = Console(*args, **kwargs)
    console.is_jupyter = False
    return console

@contextmanager
def convert_tqdm_to_rich(description: Optional[str] = None, verbose: int = 1):
    """Context manager to convert tqdm to rich."""
    import opik.evaluation.engine.evaluation_tasks_executor
    
    def _tqdm_to_track(iterable, desc, disable, total):
        disable = verbose == 0
        return track(
            iterable,
            description=description or desc,
            disable=disable,
            total=total
        )

    original__tqdm = opik.evaluation.engine.evaluation_tasks_executor._tqdm
    opik.evaluation.engine.evaluation_tasks_executor._tqdm = _tqdm_to_track


    from opik.evaluation import report
    report.display_experiment_results = lambda *args, **kwargs: None
    report.display_experiment_link = lambda *args, **kwargs: None

    try:
        yield
    finally:
        opik.evaluation.engine.evaluation_tasks_executor._tqdm = original__tqdm



@contextmanager
def suppress_opik_logs():
    """Suppress Opik startup logs by temporarily increasing the log level."""
    # Optimizer log level
    optimizer_logger = logging.getLogger('opik_optimizer')
    
    # Get the Opik logger
    opik_logger = logging.getLogger("opik.api_objects.opik_client")
    
    # Store original log level
    original_level = opik_logger.level
    
    # Set log level to ERROR to suppress INFO messages
    opik_logger.setLevel(optimizer_logger.level)
    
    try:
        yield
    finally:
        # Restore original log level
        opik_logger.setLevel(original_level)

def display_messages(messages: List[Dict[str, str]], prefix: str = ""):
    for i, msg in enumerate(messages):
        panel = Panel(
            Text(msg.get('content', ''), overflow="fold"),
            title=f"{msg.get('role', 'message')}",
            title_align="left",
            border_style="dim",
            width=PANEL_WIDTH,
            padding=(1, 2),
        )

        # Capture the panel as rendered text with ANSI styles
        console = get_console()
        with console.capture() as capture:
            console.print(panel)

        # Retrieve the rendered string (with ANSI)
        rendered_panel = capture.get()

        # Prefix each line with '| ', preserving ANSI styles
        for line in rendered_panel.splitlines():
            console.print(Text(prefix) + Text.from_ansi(line))

def display_header(
    algorithm: str,
    optimization_id: Optional[str]=None,
    dataset_id: Optional[str]=None,
    verbose: int = 1
):
    if verbose < 1:
        return

    if optimization_id is not None and dataset_id is not None:    
        optimization_url = get_optimization_run_url_by_id(
            optimization_id=optimization_id,
            dataset_id=dataset_id
        )
        
        # Create a visually appealing panel with an icon and ensure link doesn't wrap
        
        link_text = Text("-> View optimization details in your Opik dashboard")
        link_text.stylize(f"link {optimization_url}", 28, len(link_text))
    else:
        link_text = Text("No optimization run link available", style="dim")

    content = Text.assemble(
        ("● ", "green"),  
        "Running Opik Evaluation - ",
        (algorithm, "blue"),
        "\n\n"
    ).append(link_text)

    
    panel = Panel(
        content,
        box=box.ROUNDED,
        width=PANEL_WIDTH
    )

    console = get_console()
    console.print(panel)
    console.print("\n")


def display_result(initial_score, best_score, best_prompt, verbose: int = 1):
    if verbose < 1:
        return

    console = get_console()
    console.print(Text("\n> Optimization complete\n"))
    
    if best_score > initial_score:
        if initial_score == 0:
            content = [Text(f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f}", style="bold green")]
        else:
            perc_change = (best_score - initial_score) / initial_score
            content = [Text(f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})", style="bold green")]
    else:
        content = [Text(f"Optimization run did not find a better prompt than the initial one.\nScore: {best_score:.4f}", style="dim bold red")]
    
    content.append(Text("\nOptimized prompt:"))
    for i, msg in enumerate(best_prompt):
        content.append(
            Panel(
                Text(msg.get('content', ''), overflow="fold"),
                title=f"{msg.get('role', 'message')}",
                title_align="left",
                border_style="dim",
                width=PANEL_WIDTH,
                padding=(1, 2),
            )
        )

    console.print(
        Panel(
            Group(*content),
            title="Optimization results",
            title_align="left",
            border_style="green",
            width=PANEL_WIDTH,
            padding=(1, 2)
        )
    )


def display_configuration(messages: List[Dict[str, str]], optimizer_config: Dict[str, str], verbose: int = 1):
    """Displays the LLM messages and optimizer configuration using Rich panels."""

    if verbose < 1:
        return

    # Panel for Optimizer configuration
    console = get_console()
    console.print(Text("> Let's optimize the prompt:\n"))

    display_messages(messages)

    # Panel for configuration
    console.print(Text(f"\nUsing {optimizer_config['optimizer']} with the parameters: "))
    
    for key, value in optimizer_config.items():
        if key == "optimizer":  # Already displayed in the introductory text
            continue
        parameter_text = Text.assemble(
            Text(f"  - {key}: ", style="dim"), 
            Text(str(value), style="cyan")      
        )
        console.print(parameter_text)
    
    console.print("\n")

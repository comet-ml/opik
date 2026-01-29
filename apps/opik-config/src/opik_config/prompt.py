from dataclasses import dataclass


@dataclass
class Prompt:
    """Mock of opik.Prompt for use with @agent_config decorator."""

    name: str
    prompt: str

    def format(self, **kwargs) -> str:
        return self.prompt.format(**kwargs)

    def __str__(self) -> str:
        return self.prompt

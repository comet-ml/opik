"""Allow running the runner module directly: python -m opik.runner"""

from .config import load_runner_config, load_agents
from .runner import Runner


def main() -> None:
    config = load_runner_config()
    runner_id = config.get("runner_id")
    redis_url = config.get("redis_url")

    if not runner_id or not redis_url:
        print("Runner not configured. Run 'opik connect --pair CODE' first.")
        return

    agents = load_agents()
    if not agents:
        print("No agents registered. Run your @entrypoint script first.")
        return

    runner = Runner(runner_id=runner_id, redis_url=redis_url)
    runner.run()


if __name__ == "__main__":
    main()

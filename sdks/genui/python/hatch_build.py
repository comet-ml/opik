import json
from pathlib import Path

from hatchling.metadata.plugin.interface import MetadataHookInterface

HERE = Path(__file__).parent
PACKAGE_JSON = (HERE / "package.json", HERE.parent / "js" / "package.json")
DATA = HERE / "src" / "opik_genui" / "data"
ENGINE_DATA = DATA / "engine.json"


class PackageVersionHook(MetadataHookInterface):
    def update(self, metadata: dict) -> None:
        source = next((path for path in PACKAGE_JSON if path.is_file()), None)
        if source is None:
            raise RuntimeError(
                f"{PACKAGE_JSON[1]} is missing; build the wheel from sdks/genui/python"
            )
        missing = [] if ENGINE_DATA.is_file() else [ENGINE_DATA]
        if not missing:
            areas = json.loads(ENGINE_DATA.read_text())["areas"]
            prompts = (DATA / f"prompt-{area}.txt" for area in areas)
            missing = [path for path in prompts if not path.is_file()]
        if missing:
            raise RuntimeError(
                f"{', '.join(map(str, missing))} missing; run npm run build in sdks/genui first"
            )
        version = json.loads(source.read_text())["version"]
        built = json.loads(ENGINE_DATA.read_text()).get("version")
        if built != version:
            raise RuntimeError(
                f"{ENGINE_DATA} was built for version {built}, not {version}; "
                "run npm run build in sdks/genui again"
            )
        metadata["version"] = version

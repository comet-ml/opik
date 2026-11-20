import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { ENGINE_VERSION } from "../src/contract";
import { prompt } from "../src/guidance";
import { TOKENS } from "../src/tokens";
import { AREAS, LIBRARIES, MAX_HTML_LENGTH } from "../src/view";

const data = new URL("../../python/src/opik_genui/data/", import.meta.url);

const files: Record<string, string> = {
  "engine.json":
    JSON.stringify(
      {
        engine_version: ENGINE_VERSION,
        areas: AREAS,
        libraries: LIBRARIES,
        max_html_length: MAX_HTML_LENGTH,
        tokens: TOKENS,
      },
      null,
      2,
    ) + "\n",
  ...Object.fromEntries(
    AREAS.map((area) => [`prompt-${area}.txt`, prompt(area) + "\n"]),
  ),
};

rmSync(data, { recursive: true, force: true });
mkdirSync(data, { recursive: true });
for (const [name, content] of Object.entries(files)) {
  writeFileSync(new URL(name, data), content);
  console.log(`wrote python/src/opik_genui/data/${name}`);
}

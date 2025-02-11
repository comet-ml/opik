import { track, trackOpikClient } from "opik";

const llmCall = track({ type: "llm" }, async () => "llm result");

const translate = track(
  { name: "translate" },
  async (text) => `translated: ${text}`
);

const execute = track(
  { name: "initial", projectName: "track-decorator-test" },
  async () => {
    const result = await llmCall();
    return translate(result);
  }
);

await execute();
await trackOpikClient.flush();

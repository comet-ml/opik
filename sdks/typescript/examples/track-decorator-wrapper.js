import { track, trackOpikClient } from "opik";

const llmCall = track(
  { name: "llm-test", type: "llm" },
  async () => "llm result"
);

const translate = track(
  { name: "translate", type: "tool" },
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

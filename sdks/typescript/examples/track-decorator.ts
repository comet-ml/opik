import { flushAll, track } from "opik";

class TestClass {
  @track({ type: "llm" })
  async llmCall() {
    return "llm result";
  }

  @track({ name: "translate" })
  async translate(text: string) {
    return `translated: ${text}`;
  }

  @track({ name: "initial", projectName: "track-decorator-test" })
  async execute() {
    const result = await this.llmCall();
    return this.translate(result);
  }
}

const test = new TestClass();
await test.execute();
await flushAll();

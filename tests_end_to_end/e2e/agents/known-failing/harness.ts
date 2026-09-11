import * as path from 'node:path';
import { runBridgePython } from '@e2e/core/local-runner/connect';

/**
 * Test helpers specific to the known-failing golden agent (agents/known-failing).
 * They run the agent's `run()` through the bridge venv (its `@opik.track`
 * decorators need opik importable) and read its `suite.json` as the single
 * source of truth for the eval items. Kept beside the agent, not in the generic
 * Local-Runner helper, because they encode this agent's suite shape.
 */

export interface SuitePassRate {
  passed: number;
  total: number;
  rate: number;
}

/** Build the Python preamble that imports the agent module from `agentDir`. */
function importAgent(agentDir: string): string {
  return [
    'import importlib.util, json, os',
    `d = ${JSON.stringify(agentDir)}`,
    'spec = importlib.util.spec_from_file_location("kf", os.path.join(d, "agent.py"))',
    'kf = importlib.util.module_from_spec(spec); spec.loader.exec_module(kf)',
    'suite = json.load(open(os.path.join(d, "suite.json")))',
  ].join('\n');
}

/**
 * Evaluate the agent in `agentDir` against its `suite.json`: run each item's
 * question through `run(...)` and count exact-match passes. Used to measure the
 * pass rate before and after the Ollie /improve flow edits the agent — the test
 * asserts the *direction*, not a value, since /improve may land different fixes.
 * A fresh import each call reflects any /improve edit to the agent source.
 */
export async function evaluatePassRate(agentDir: string): Promise<SuitePassRate> {
  const code = `${importAgent(agentDir)}
passed = sum(kf.run(i["data"]["question"]) == i["expected"] for i in suite["items"])
print(json.dumps({"passed": passed, "total": len(suite["items"])}))`;
  const stdout = await runBridgePython(code, {}, agentDir);
  const line = stdout.trim().split('\n').pop() ?? '{}';
  const { passed, total } = JSON.parse(line) as { passed: number; total: number };
  return { passed, total, rate: total ? passed / total : 0 };
}

/**
 * Run the agent over its suite with tracing enabled, logging a trace per item
 * to `projectName`. Ollie's /improve flow only surfaces once the project has
 * traces to improve from, so this seeds the failing runs it works on.
 */
export async function seedFailingTraces(
  agentDir: string,
  opts: { projectName: string; workspace: string; apiKey: string; apiUrl: string },
): Promise<void> {
  const code = `import opik
${importAgent(agentDir)}
for i in suite["items"]:
    kf.run(i["data"]["question"])
opik.flush_tracker()`;
  await runBridgePython(
    code,
    {
      OPIK_API_KEY: opts.apiKey,
      OPIK_WORKSPACE: opts.workspace,
      OPIK_URL_OVERRIDE: opts.apiUrl,
      OPIK_PROJECT_NAME: opts.projectName,
    },
    agentDir,
  );
}

/** Absolute path to the known-failing golden agent directory. */
export const KNOWN_FAILING_DIR = path.resolve(__dirname);

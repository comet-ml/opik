import { test as baseTest } from './traced-agent.fixture';

export interface ConversationTurnRef {
  traceId: string;
  input: string;
  output: string;
}

export interface ConversationRef {
  threadId: string;
  projectId: string;
  projectName: string;
  /** Turns in the order they were logged (oldest first). */
  turns: ConversationTurnRef[];
}

export interface ConversationFixtures {
  conversation: ConversationRef;
}

/** Plain-string turns so the conversation view renders them verbatim (a string
 * input/output bypasses the chat-shape prettifiers and shows as-is). */
const TURNS = [
  { input: 'What is the capital of France?', output: 'The capital of France is Paris.' },
  { input: 'What is its population?', output: 'Paris has about 2.1 million residents.' },
  { input: 'Name a famous landmark there.', output: 'The Eiffel Tower is its most famous landmark.' },
] as const;

/**
 * A multi-turn conversation: each turn is a single trace, and all turns share
 * one thread_id so the Logs Threads view groups them into one conversation.
 * Turns are seeded sequentially with a short gap so their chronological order
 * is deterministic. Seeded through the public SDK bridge so the same shape
 * lands on OSS and Cloud.
 */
export const test = baseTest.extend<ConversationFixtures>({
  conversation: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const threadId = `${testNamespace}-thread`;

    const turns: ConversationTurnRef[] = [];
    for (let i = 0; i < TURNS.length; i++) {
      const { input, output } = TURNS[i];
      const created = await sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-turn-${i + 1}`,
        input,
        output,
        thread_id: threadId,
      });
      turns.push({ traceId: created.id, input, output });
      if (i < TURNS.length - 1) {
        await new Promise((r) => setTimeout(r, 50));
      }
    }

    const ref: ConversationRef = {
      threadId,
      projectId: project.id,
      projectName: project.name,
      turns,
    };

    await testInfo.attach('opik.conversation', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './traced-agent.fixture';

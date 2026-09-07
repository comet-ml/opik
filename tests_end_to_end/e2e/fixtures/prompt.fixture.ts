import { test as baseTest } from './project.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

const TEXT_TEMPLATE = 'You are a helpful assistant. Answer the following: {{question}}';

const CHAT_MESSAGES = [
  { role: 'system', content: 'You are a helpful assistant.' },
  { role: 'user', content: 'Answer the following: {{question}}' },
];

export interface TextPromptRef {
  id: string;
  name: string;
  template: string;
}

export interface ChatPromptRef {
  id: string;
  name: string;
  messages: Array<{ role: string; content: string }>;
}

export interface PromptFixtures {
  textPrompt: TextPromptRef;
  chatPrompt: ChatPromptRef;
  bystanderPrompt: TextPromptRef;
  registerPromptCleanup: (id: string, name: string) => void;
}

export const test = baseTest.extend<PromptFixtures>({
  textPrompt: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-text-prompt`;
    const created = await sdkClient.python.createTextPrompt({
      name,
      prompt: TEXT_TEMPLATE,
      project_name: project.name,
    });
    const ref: TextPromptRef = { id: created.id, name: created.name, template: TEXT_TEMPLATE };
    await testInfo.attach('opik.text-prompt', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(created.id);
      } catch (err) {
        console.warn(`[textPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },

  /**
   * A prompt the test must NOT touch, so a delete that wiped the whole library
   * cannot pass as a delete of one row.
   *
   * Resolves the id by name rather than using the one create returned: the
   * Python SDK hands back the prompt VERSION id, which `DELETE
   * /v1/private/prompts/{id}` answers 404 for, so teardown would silently
   * leak the prompt.
   */
  bystanderPrompt: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-prompt-bystander`;
    const template = 'Bystander prompt: {{question}}';
    await sdkClient.python.createTextPrompt({
      name,
      prompt: template,
      project_name: project.name,
    });
    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[bystanderPrompt fixture] could not resolve id for "${name}" after create`);
    }
    const ref: TextPromptRef = { id, name, template };
    await testInfo.attach('opik.bystander-prompt', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[bystanderPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },

  registerPromptCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: Array<{ id: string; name: string }> = [];
    await use((id, name) => {
      registry.push({ id, name });
    });
    if (!shouldLeaveArtifacts(testInfo)) {
      for (const { id, name } of registry) {
        try {
          await backendClient.deletePrompt(id);
        } catch (err) {
          console.warn(`[registerPromptCleanup] delete warning for ${name}:`, err);
        }
      }
    }
  },

  chatPrompt: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-chat-prompt`;
    const created = await sdkClient.python.createChatPrompt({
      name,
      messages: CHAT_MESSAGES,
      project_name: project.name,
    });
    const ref: ChatPromptRef = { id: created.id, name: created.name, messages: CHAT_MESSAGES };
    await testInfo.attach('opik.chat-prompt', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(created.id);
      } catch (err) {
        console.warn(`[chatPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './project.fixture';

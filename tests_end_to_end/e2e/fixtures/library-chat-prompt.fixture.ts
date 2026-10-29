import { expect } from '@playwright/test';
import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * The messages each seeded version carries.
 *
 * Every string is unique across the fixture so an assertion on the editors can
 * say WHICH version is on screen, not merely that something is. A spec whose
 * versions shared a line could not tell "v1 is still applied" from "v2 was
 * applied over it".
 */
export const V1_MESSAGES = [
  { role: 'system' as const, content: 'SYS-V1-LIBRARY' },
  { role: 'user' as const, content: 'USER-V1-LIBRARY' },
];

/** For a second commit, published by a spec through `publishVersion`. */
export const V2_MESSAGES = [
  { role: 'system' as const, content: 'SYS-V2-LIBRARY' },
  { role: 'user' as const, content: 'USER-V2-LIBRARY' },
];

export const DECOY_MESSAGES = [
  { role: 'system' as const, content: 'SYS-DECOY-LIBRARY' },
  { role: 'user' as const, content: 'USER-DECOY-LIBRARY' },
];

export interface LibraryChatPromptRef {
  /** The id `GET`/`DELETE /v1/private/prompts/{id}` expect, not a version id. */
  id: string;
  name: string;
  /** The prompt's first commit, as the backend recorded it. */
  v1: PromptVersionRef;
  /**
   * Commit another version of the same prompt and return the backend's own
   * record of it.
   *
   * A callback rather than a second seeded version because the scenarios that
   * need one need it to appear WHILE an unsaved edit is on screen — a version
   * seeded up front would be a different story, one where the library had
   * already moved on before the user started typing.
   */
  publishVersion(
    messages: Array<{ role: 'system' | 'user'; content: string }>,
  ): Promise<PromptVersionRef>;
  /**
   * The commit whose template is exactly `messages`, whoever wrote it — for a
   * spec that saved a version through the UI and needs the id the backend gave
   * it.
   */
  versionWithTemplate(
    messages: Array<{ role: string; content: string }>,
  ): Promise<PromptVersionRef>;
}

export interface LibraryChatPromptFixtures {
  libraryChatPrompt: LibraryChatPromptRef;
  decoyChatPrompt: LibraryChatPromptRef;
}

/**
 * A chat prompt in the library, at one version, plus a second one to pick
 * instead of it.
 *
 * Seeded through the public SDK (`client.create_chat_prompt`) rather than by
 * posting a version directly: the Playground's library menu lists only prompts
 * whose `template_structure` is `chat`, and the SDK is what sets it.
 *
 * Teardown deletes the prompt, which takes every commit with it. The id comes
 * from `findPromptIdByName`, never from the create response: the SDK hands back
 * the prompt VERSION id, and `DELETE /v1/private/prompts/{id}` answers 404 for
 * one of those, so teardown registered with it would leak in silence.
 */
export const test = baseTest.extend<LibraryChatPromptFixtures>({
  libraryChatPrompt: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-library-chat`;
    await sdkClient.python.createChatPrompt({
      name,
      messages: V1_MESSAGES,
      project_name: project.name,
    });

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[libraryChatPrompt fixture] could not resolve prompt id for "${name}"`);
    }

    const versionWithTemplateOf = async (
      messages: Array<{ role: string; content: string }>,
    ): Promise<PromptVersionRef> => {
      const wanted = JSON.stringify(messages.map(({ role, content }) => ({ role, content })));
      // Matched on the template rather than taken as "the newest": the specs
      // using this pin a card to a version id, so picking the wrong commit
      // would make every later assertion a statement about another version.
      const matching = async (): Promise<PromptVersionRef[]> =>
        (await backendClient.listPromptVersions(id)).filter((version) => {
          try {
            const parsed = JSON.parse(version.template) as Array<{
              role: string;
              content: string;
            }>;
            return (
              JSON.stringify(parsed.map(({ role, content }) => ({ role, content }))) === wanted
            );
          } catch {
            return false;
          }
        });

      // Polled because a caller may be resolving a commit the UI has just
      // written: the save dialog closes on its own mutation, which is not
      // quite the same instant the version is listable.
      await expect
        .poll(async () => (await matching()).length, {
          message: `"${name}" has exactly one version whose template is ${wanted}`,
          timeout: 15_000,
        })
        .toBe(1);
      return (await matching())[0];
    };

    const ref: LibraryChatPromptRef = {
      id,
      name,
      v1: await versionWithTemplateOf(V1_MESSAGES),
      publishVersion: async (messages) => {
        await sdkClient.python.createChatPrompt({
          name,
          messages,
          project_name: project.name,
        });
        return versionWithTemplateOf(messages);
      },
      versionWithTemplate: versionWithTemplateOf,
    };

    await testInfo.attach('opik.libraryChatPrompt', {
      body: JSON.stringify({ id, name, v1: ref.v1 }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[libraryChatPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },

  /**
   * A second library chat prompt, so a spec can make a DELIBERATE pick.
   *
   * Named so it shares no substring with `libraryChatPrompt`'s name: the
   * library menu's search is a substring match, and a name that contained the
   * other would make the row the POM clicks ambiguous.
   */
  decoyChatPrompt: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const name = `${testNamespace}-decoy-chat`;
    await sdkClient.python.createChatPrompt({
      name,
      messages: DECOY_MESSAGES,
      project_name: project.name,
    });

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[decoyChatPrompt fixture] could not resolve prompt id for "${name}"`);
    }
    const versions = await backendClient.listPromptVersions(id);
    if (versions.length !== 1) {
      throw new Error(
        `[decoyChatPrompt fixture] "${name}" has ${versions.length} versions, expected exactly 1`,
      );
    }

    const ref: LibraryChatPromptRef = {
      id,
      name,
      v1: versions[0],
      publishVersion: async () => {
        throw new Error('[decoyChatPrompt fixture] the decoy is never versioned');
      },
      versionWithTemplate: async () => {
        throw new Error('[decoyChatPrompt fixture] the decoy has only v1');
      },
    };

    await testInfo.attach('opik.decoyChatPrompt', {
      body: JSON.stringify({ id, name, v1: ref.v1 }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[decoyChatPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './prompt.fixture';

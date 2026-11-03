// Chained off `prompt.fixture` and not `./index`, for the same reason
// `chat-prompt-versions.fixture` is: the Playground specs that use this also
// need `providerKeys` (the page auto-opens its provider-setup dialog over
// itself when the workspace has none), and that lives on the far end of the
// chain past the re-exported middle.
import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient, PromptVersionRef } from '../core/backend';

/**
 * The metadata that makes the front end read `template` as structured message
 * content instead of plain text — `isMessagesJsonFormat` in `lib/llm.ts` tests
 * for exactly this pair, and `parsePromptVersionRole` reads the role out of the
 * parsed array.
 *
 * A seed that omits it is read back as a plain string whatever the template
 * holds, so a role-preservation assertion over one would be asserting about a
 * prompt the UI never saw as having a role at all.
 */
export const MESSAGES_JSON_METADATA = {
  created_from: 'opik_ui',
  type: 'messages_json',
} as const;

export type PromptMessageRole = 'system' | 'user' | 'assistant';

/** One message, in the shape a Playground TEXT-prompt save writes it. */
export const textPromptTemplate = (role: PromptMessageRole, content: string): string =>
  JSON.stringify([{ role, content }], null, 2);

/**
 * A chat template, in the shape `useLoadChatPrompt` writes it — compact, and in
 * that key order, so a version this fixture seeded and one the UI saved are
 * byte-comparable.
 */
export const chatPromptTemplate = (
  messages: ReadonlyArray<{ role: PromptMessageRole; content: string }>,
): string => JSON.stringify(messages.map(({ role, content }) => ({ role, content })));

export interface SeededLibraryPrompt {
  /** The id `GET`/`DELETE /v1/private/prompts/{id}` expect. */
  id: string;
  name: string;
  /** Every committed version, oldest first — `[0]` is v1. */
  versions: PromptVersionRef[];
  /** The `vN` labels the backend assigned, oldest first. */
  labels: string[];
}

export interface ProjectPromptLibrary {
  /**
   * A text-structured prompt with one committed version per entry of `bodies`,
   * oldest first, every version carrying `role`.
   */
  seedTextPrompt(args: {
    /** Appended to the test namespace to make the library name. */
    suffix: string;
    role: PromptMessageRole;
    bodies: readonly string[];
  }): Promise<SeededLibraryPrompt>;
  /** A chat-structured prompt holding `messages` as its single version. */
  seedChatPrompt(args: {
    suffix: string;
    messages: ReadonlyArray<{ role: PromptMessageRole; content: string }>;
  }): Promise<SeededLibraryPrompt>;
}

export interface ProjectPromptLibraryFixtures {
  promptLibrary: ProjectPromptLibrary;
}

/**
 * Commit the versions and then prove what landed.
 *
 * `createPromptVersion` answers 200 whether it committed a new version or
 * deduped onto an existing one, and it honours `template_structure` only on the
 * call that CREATES the prompt — so the return values of the loop cannot tell a
 * text prompt from a chat one, nor three commits from one. The read-back is
 * therefore not belt-and-braces: without it, a spec whose whole subject is
 * "which type did that save write" could be driving a library whose types were
 * never what it asked for, and would read as coverage forever.
 *
 * The id is resolved by NAME: the version endpoints answer with version ids and
 * `DELETE /v1/private/prompts/{id}` 404s for one of those, so teardown
 * registered with it leaks the prompt behind its own catch.
 */
async function commitAndVerify(
  backendClient: BackendClient,
  args: {
    name: string;
    projectId: string;
    templateStructure: 'text' | 'chat';
    templates: readonly string[];
  },
): Promise<SeededLibraryPrompt> {
  const versions: PromptVersionRef[] = [];
  for (const [index, template] of args.templates.entries()) {
    versions.push(
      await backendClient.createPromptVersion({
        name: args.name,
        template,
        metadata: MESSAGES_JSON_METADATA,
        projectId: args.projectId,
        // Only on the create, which is the only call that honours it.
        ...(index === 0 ? { templateStructure: args.templateStructure } : {}),
      }),
    );
  }

  const stored = await backendClient.getPromptByName(args.name, args.projectId);
  if (stored.templateStructure !== args.templateStructure) {
    throw new Error(
      `[promptLibrary] "${args.name}" stored template_structure ` +
        `${JSON.stringify(stored.templateStructure)}, asked for "${args.templateStructure}"`,
    );
  }
  if (stored.versionCount !== args.templates.length) {
    throw new Error(
      `[promptLibrary] "${args.name}" holds ${stored.versionCount} version(s), ` +
        `expected ${args.templates.length} — the seed did not commit what it was asked to`,
    );
  }

  const labels = versions.map((version, index) => {
    if (!version.versionNumber) {
      throw new Error(
        `[promptLibrary] version ${index + 1} of "${args.name}" came back with no version_number`,
      );
    }
    return version.versionNumber;
  });

  return { id: stored.id, name: args.name, versions, labels };
}

/**
 * A builder for ONE project's prompt library, with teardown.
 *
 * A builder rather than a seed fixture because the library's own SHAPE is what
 * the specs using it are about: how many prompt types it holds is what decides
 * whether the variant header's Load asks for a type first
 * (`PromptLibraryMenu.hasBothStructures`), and whether it holds anything at all
 * is what decides whether that control is enabled. A fixture that seeded one
 * fixed shape could express neither, and five fixtures — text-only, chat-only,
 * both, empty, two-version — would be five near-copies of this.
 *
 * Teardown deletes every prompt it created, which takes their versions with
 * them, and runs on pass, fail and timeout alike. `OPIK_LEAVE_FAILURES` is
 * honoured so a deliberate evidence-keeping run keeps the library it built.
 *
 * Names are built from `testNamespace`, so `global-teardown`'s run-prefix sweep
 * catches anything a hard crash left behind — prompts do NOT cascade with the
 * project.
 */
export const test = baseTest.extend<ProjectPromptLibraryFixtures>({
  promptLibrary: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const created: SeededLibraryPrompt[] = [];

    const record = (prompt: SeededLibraryPrompt): SeededLibraryPrompt => {
      created.push(prompt);
      return prompt;
    };

    await use({
      async seedTextPrompt({ suffix, role, bodies }) {
        return record(
          await commitAndVerify(backendClient, {
            name: `${testNamespace}-${suffix}`,
            projectId: project.id,
            templateStructure: 'text',
            templates: bodies.map((body) => textPromptTemplate(role, body)),
          }),
        );
      },

      async seedChatPrompt({ suffix, messages }) {
        return record(
          await commitAndVerify(backendClient, {
            name: `${testNamespace}-${suffix}`,
            projectId: project.id,
            templateStructure: 'chat',
            templates: [chatPromptTemplate(messages)],
          }),
        );
      },
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const { id, name } of created) {
        try {
          await backendClient.deletePrompt(id);
        } catch (err) {
          console.warn(`[promptLibrary fixture] delete warning for ${name}:`, err);
        }
      }
    }
  },
});

export { expect } from './prompt.fixture';

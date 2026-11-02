// Chained off `prompt.fixture` and not `./index`, for the same reason
// `media-prompt.fixture` and `versioned-prompt.fixture` are: the Playground
// specs that use this also need `providerKeys` (the page does not mount without
// one) and `registerPromptCleanup`, and both live on the far end of the chain
// past the re-exported middle.
import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * The metadata that makes the front end read `template` as structured message
 * content instead of plain text — `isMessagesJsonFormat` in `lib/llm.ts` tests
 * for exactly this pair.
 *
 * It is also half of what the dedup compares. `AddNewPromptVersionDialog` seeds
 * its metadata field from the loaded version's own (`getInitialMetadata`) and
 * posts it straight back, so a seed carrying this pair produces a save that
 * carries it too — and one carrying none would differ from the UI's save in a
 * field that has nothing to do with the behaviour under test.
 */
const MESSAGES_JSON_METADATA = {
  created_from: 'opik_ui',
  type: 'messages_json',
} as const;

/**
 * One distinct body per version, oldest first — three, so that "the latest" and
 * "not the latest" are both reachable with a version in between that is neither.
 *
 * Distinct rather than identical: the prompt must arrive at three COMMITTED
 * versions, and seeding the same body three times is exactly what the dedup
 * under test would collapse into one.
 */
export const CHAT_VERSION_CONTENTS = [
  'Say hello in exactly one word.',
  'Say goodbye in exactly one word.',
  'What is the capital of France?',
] as const;

/**
 * The stored template for one version, in the shape the Playground's own save
 * writes — `useLoadChatPrompt`'s `chatPromptTemplate` is
 * `JSON.stringify(messages.map(({ role, content }) => ...))`, compact and in
 * that key order.
 *
 * Written to match it byte for byte even though the backend's CHAT comparison
 * normalises whitespace and key order: matching only after normalisation would
 * make these specs depend on that normalisation as well as on the dedup, and a
 * change to either would then fail them for reasons a reader could not separate.
 */
export const chatTemplateFor = (content: string): string =>
  JSON.stringify([{ role: 'user', content }]);

export interface ChatPromptVersionsRef {
  /** The id `GET`/`DELETE /v1/private/prompts/{id}` expect. */
  id: string;
  name: string;
  /** Every committed version, oldest first — `[0]` is v1, `[2]` is v3. */
  versions: PromptVersionRef[];
  /** The `vN` labels the backend assigned, oldest first. */
  labels: string[];
  /** The latest committed version — the one an unchanged save must dedup to. */
  latest: PromptVersionRef;
}

export interface ChatPromptVersionsFixtures {
  chatPromptVersions: ChatPromptVersionsRef;
}

/**
 * A chat-structured prompt with three committed versions, each a single user
 * message, stored exactly as a Playground save would have written it.
 *
 * Seeded through `POST /v1/private/prompts/versions` rather than the Python
 * SDK, which is not a style choice: `create_prompt` dedups CLIENT-side before
 * its request is sent, so an SDK seed can neither reach the backend predicate
 * these specs are about nor be trusted to have committed what it was asked to.
 * `template_structure: chat` goes on the first version — the call that creates
 * the prompt — because the backend ignores it for a name that already exists
 * and the structure is immutable after that; it is what puts the prompt in the
 * chat card's own library menu rather than the message row's.
 *
 * ## It proves it discriminates before any browser opens
 *
 * Both specs using this read a version COUNT, and "the count did not grow" is
 * equally true of a prompt that never reached three versions — a seed whose
 * second and third commits had silently deduped would turn the no-op save spec
 * into a test that cannot fail. So the fixture reads the versions back and
 * asserts there are three, with three distinct ids, three distinct commits and
 * the server's own v1/v2/v3 labels, before handing them over.
 *
 * Teardown deletes the prompt, which takes its versions with it. The id is
 * resolved by name: the version endpoints answer with version ids, and
 * `DELETE /v1/private/prompts/{id}` 404s for one of those, so teardown
 * registered with it would leak the prompt behind its own catch.
 */
export const test = baseTest.extend<ChatPromptVersionsFixtures>({
  chatPromptVersions: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-chat-versions`;

    const versions: PromptVersionRef[] = [];
    for (const [i, content] of CHAT_VERSION_CONTENTS.entries()) {
      versions.push(
        await backendClient.createPromptVersion({
          name,
          template: chatTemplateFor(content),
          metadata: MESSAGES_JSON_METADATA,
          projectId: project.id,
          // Only on the create, which is the only call that honours it.
          ...(i === 0 ? { templateStructure: 'chat' as const } : {}),
          // Deliberately no `changeDescription`: setting one is a documented
          // dedup opt-out, and the latest version carrying one would make the
          // UI's save differ from it in a field the specs are not about. The
          // dialog itself posts none (an empty string is dropped by
          // `useCreatePromptVersionMutation`), so this matches it.
        }),
      );
    }

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[chatPromptVersions fixture] could not resolve prompt id for "${name}"`);
    }

    // Read back rather than trust the three creates: a create that deduped
    // answers with the EXISTING version and a 200, so the loop above cannot
    // tell three commits from one by its own return values alone.
    const stored = await backendClient.listPromptVersions(id);
    if (stored.length !== CHAT_VERSION_CONTENTS.length) {
      throw new Error(
        `[chatPromptVersions fixture] "${name}" holds ${stored.length} version(s), ` +
          `expected ${CHAT_VERSION_CONTENTS.length} — the seed did not commit what it was asked to`,
      );
    }

    const labels = versions.map((v, i) => {
      if (!v.versionNumber) {
        throw new Error(
          `[chatPromptVersions fixture] version ${i + 1} of "${name}" came back with no version_number`,
        );
      }
      if (!v.commit) {
        throw new Error(
          `[chatPromptVersions fixture] version ${i + 1} of "${name}" came back with no commit`,
        );
      }
      return v.versionNumber;
    });

    // Distinct ids AND distinct commits: these are the two fields a dedup
    // assertion compares, and a seed in which any pair of them collided would
    // let "the server answered with the latest version" pass against the wrong
    // version entirely.
    if (new Set(versions.map((v) => v.id)).size !== versions.length) {
      throw new Error(`[chatPromptVersions fixture] "${name}" has duplicate version ids`);
    }
    if (new Set(versions.map((v) => v.commit)).size !== versions.length) {
      throw new Error(`[chatPromptVersions fixture] "${name}" has duplicate version commits`);
    }

    const ref: ChatPromptVersionsRef = {
      id,
      name,
      versions,
      labels,
      latest: versions[versions.length - 1],
    };

    await testInfo.attach('opik.chatPromptVersions', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[chatPromptVersions fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './prompt.fixture';

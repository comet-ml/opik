import { test as baseTest } from './openrouter-cost-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * The metadata that makes the front end read `template` as structured message
 * content instead of plain text — `isMessagesJsonFormat` in `lib/llm.ts` tests
 * for exactly this pair. Without it `parsePromptVersionContent` hands the raw
 * JSON string back as the message body and the seed would carry no media part
 * at all. Same pair `mediaPrompt` uses.
 */
const MESSAGES_JSON_METADATA = {
  created_from: 'opik_ui',
  type: 'messages_json',
} as const;

/** The text part of the seeded version, so the message is runnable but for the image. */
export const IMAGE_ONLY_PROMPT_TEXT = 'Describe this picture in one sentence.';

/**
 * The one media part — an image, and nothing else.
 *
 * A plain `https` URL rather than base64 so the tag renders its URL as text,
 * which is the handle a spec addresses it by; nothing fetches it, and a failed
 * preview is a tooltip no spec opens.
 */
export const IMAGE_ONLY_PROMPT_IMAGE_URL = 'https://example.com/opik-e2e-image-only.png';

/**
 * The content array as the Playground's own save would have written it.
 *
 * Exported so a spec can compare the message against the WHOLE shape rather
 * than a part count: a load that kept the image but reordered or rewrote a part
 * satisfies a count and not this.
 */
export const IMAGE_ONLY_PROMPT_CONTENT = [
  { type: 'text', text: IMAGE_ONLY_PROMPT_TEXT },
  { type: 'image_url', image_url: { url: IMAGE_ONLY_PROMPT_IMAGE_URL } },
];

export interface ImageOnlyPromptRef {
  /** The id `GET`/`DELETE /v1/private/prompts/{id}` expect. */
  id: string;
  name: string;
  /** The single committed version, as the server numbered it. */
  version: PromptVersionRef;
  /** The `vN` label the library menu lists the version under. */
  versionLabel: string;
  imageUrl: string;
}

export interface ImageOnlyPromptFixtures {
  imageOnlyPrompt: ImageOnlyPromptRef;
}

/**
 * A one-version prompt whose stored content is a message with a text part and
 * an IMAGE part — and no video and no audio.
 *
 * ## Why not `mediaPrompt`
 *
 * That fixture seeds all three media types at once, which is right for the
 * role-aware drop rule it was written for and wrong for anything about the RUN
 * GATE. `hasUnsupportedMedia` is a disjunction over the three types
 * independently, so a message carrying video and audio is refused by every
 * model whose row does not claim those too — including the native vision model
 * a gate spec needs as its positive control. With all three parts, "Run is
 * enabled" is unreachable and the control cannot exist.
 *
 * Seeded through `POST /v1/private/prompts/versions` with the UI's own
 * `messages_json` metadata rather than through the Playground: creating it in
 * the UI is not what this is about, and the media add-popovers are a much
 * slower and less exact way to arrive at a known content array.
 *
 * ## It proves it discriminates before any browser opens
 *
 * The version is read back and the whole content compared, because every spec
 * using this asserts something about an image being PRESENT in the message —
 * and a silently text-only seed would make "the model accepts this message" a
 * test that cannot fail.
 *
 * Teardown deletes the prompt, which takes its versions with it. The id is
 * resolved by name: the version endpoints answer with version ids, and
 * `DELETE /v1/private/prompts/{id}` 404s for one of those, so teardown
 * registered with it would leak the prompt behind its own catch.
 */
export const test = baseTest.extend<ImageOnlyPromptFixtures>({
  imageOnlyPrompt: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-image-prompt`;

    const version = await backendClient.createPromptVersion({
      name,
      template: JSON.stringify([{ role: 'user', content: IMAGE_ONLY_PROMPT_CONTENT }], null, 2),
      metadata: MESSAGES_JSON_METADATA,
      projectId: project.id,
      changeDescription: 'seeded: text plus a single image part',
    });

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[imageOnlyPrompt fixture] could not resolve prompt id for "${name}"`);
    }

    // The label is the backend's, not "v1" assumed: a spec picks the version
    // out of the library menu by the label the server assigned it.
    if (!version.versionNumber) {
      throw new Error(
        `[imageOnlyPrompt fixture] version of "${name}" came back with no version_number`,
      );
    }

    const stored = await backendClient.getPromptVersion(version.id);
    if (stored.template === undefined) {
      throw new Error(`[imageOnlyPrompt fixture] version of "${name}" came back with no template`);
    }
    const storedContent = (
      JSON.parse(stored.template) as Array<{ role: string; content: unknown }>
    )[0]?.content;
    // The whole content, not a part count: two text parts would satisfy a
    // length check and carry no image at all, which is precisely the silently
    // undiscriminating seed this guard rules out.
    if (JSON.stringify(storedContent) !== JSON.stringify(IMAGE_ONLY_PROMPT_CONTENT)) {
      throw new Error(
        `[imageOnlyPrompt fixture] "${name}" did not store the text-plus-image content; ` +
          `got ${JSON.stringify(storedContent)}`,
      );
    }

    const ref: ImageOnlyPromptRef = {
      id,
      name,
      version,
      versionLabel: version.versionNumber as string,
      imageUrl: IMAGE_ONLY_PROMPT_IMAGE_URL,
    };

    await testInfo.attach('opik.imageOnlyPrompt', {
      body: JSON.stringify({ ...ref, storedTemplate: stored.template }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[imageOnlyPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './openrouter-cost-spans.fixture';

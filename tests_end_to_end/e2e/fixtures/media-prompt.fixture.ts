// Chained off `prompt.fixture` and not `./index`, for the same reason
// `versioned-prompt.fixture` is: the Playground specs that use this also need
// `providerKeys` (the page does not mount without one) and `registerPromptCleanup`,
// and both live on the far end of the chain past the re-exported middle.
import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * The metadata that makes the front end read `template` as structured message
 * content instead of plain text — `isMessagesJsonFormat` in `lib/llm.ts` tests
 * for exactly this pair. Without it `parsePromptVersionContent` hands the raw
 * JSON string back as the message body, and the seed would never carry media
 * parts at all.
 */
const MESSAGES_JSON_METADATA = {
  created_from: 'opik_ui',
  type: 'messages_json',
} as const;

/** The text part of the seeded version — what a system/assistant load must keep. */
export const MEDIA_PROMPT_TEXT = 'Describe {{topic}} in one sentence.';

/**
 * The three media parts, one per type the message editor knows about.
 *
 * All three, not one: `toContentForRole` decides by `hasImagesInContent ||
 * hasVideosInContent || hasAudiosInContent`, so a seed carrying only an image
 * would leave two thirds of that condition unexercised. Plain `https` URLs
 * rather than base64 so the tag renders its URL as text, which is the handle
 * the specs address it by; nothing fetches them, and a failed preview is a
 * tooltip the specs never open.
 */
export const MEDIA_PROMPT_IMAGE_URL = 'https://example.com/opik-e2e-media.png';
export const MEDIA_PROMPT_VIDEO_URL = 'https://example.com/opik-e2e-media.mp4';
export const MEDIA_PROMPT_AUDIO_URL = 'https://example.com/opik-e2e-media.mp3';

/**
 * The content array as the Playground's own save would have written it.
 *
 * Exported so a spec can compare what the message (or the stored version) holds
 * against the WHOLE shape rather than against a part count: a load that kept
 * the media but reordered or rewrote a part satisfies a count and not this.
 */
export const MEDIA_PROMPT_CONTENT = [
  { type: 'text', text: MEDIA_PROMPT_TEXT },
  { type: 'image_url', image_url: { url: MEDIA_PROMPT_IMAGE_URL } },
  { type: 'video_url', video_url: { url: MEDIA_PROMPT_VIDEO_URL } },
  { type: 'audio_url', audio_url: { url: MEDIA_PROMPT_AUDIO_URL } },
];

export interface MediaPromptRef {
  /** The id `GET`/`DELETE /v1/private/prompts/{id}` expect. */
  id: string;
  name: string;
  /** The single committed version, as the server numbered it. */
  version: PromptVersionRef;
  /** The `vN` label the library menu lists the version under. */
  versionLabel: string;
  /** Every media URL the version carries, in template order. */
  mediaUrls: string[];
}

export interface MediaPromptFixtures {
  mediaPrompt: MediaPromptRef;
}

/**
 * A one-version text prompt whose stored content is a message with a text part
 * and an image, a video and an audio part — the shape the Playground writes
 * when a user adds media to a message and saves it to the library (opik#8873).
 *
 * Seeded through `POST /v1/private/prompts/versions` with the UI's own
 * `messages_json` metadata rather than through the Playground: creating it in
 * the UI is not what these specs are about, and the media add-popovers are a
 * much slower and less exact way to arrive at a known content array.
 *
 * ## It proves it discriminates before any browser opens
 *
 * The fixture reads the version back and asserts the server really stored all
 * four parts under the metadata that makes them parse. Every spec using it
 * asserts that media is DROPPED on a system or assistant row, and "no media
 * arrived" is equally true of a seed that never carried any — so a silently
 * text-only seed would turn each of those into a test that cannot fail.
 *
 * Teardown deletes the prompt, which takes its versions with it. The id is
 * resolved by name: the version endpoints answer with version ids, and
 * `DELETE /v1/private/prompts/{id}` 404s for one of those, so teardown
 * registered with it would leak the prompt behind its own catch.
 */
export const test = baseTest.extend<MediaPromptFixtures>({
  mediaPrompt: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-media-prompt`;

    const version = await backendClient.createPromptVersion({
      name,
      template: JSON.stringify([{ role: 'user', content: MEDIA_PROMPT_CONTENT }], null, 2),
      metadata: MESSAGES_JSON_METADATA,
      projectId: project.id,
      changeDescription: 'seeded: text plus image, video and audio parts',
    });

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[mediaPrompt fixture] could not resolve prompt id for "${name}"`);
    }

    // The label is the backend's, not "v1" assumed: a spec picks the version out
    // of the library menu by the label the server assigned it.
    if (!version.versionNumber) {
      throw new Error(
        `[mediaPrompt fixture] version of "${name}" came back with no version_number`,
      );
    }

    const stored = await backendClient.getPromptVersion(version.id);
    if (stored.template === undefined) {
      throw new Error(`[mediaPrompt fixture] version of "${name}" came back with no template`);
    }
    const storedContent = (
      JSON.parse(stored.template) as Array<{ role: string; content: unknown }>
    )[0]?.content;
    // The whole content, not a part count: four text parts would satisfy a
    // length check and carry no media at all, which is precisely the silently
    // undiscriminating seed this guard exists to rule out.
    if (JSON.stringify(storedContent) !== JSON.stringify(MEDIA_PROMPT_CONTENT)) {
      throw new Error(
        `[mediaPrompt fixture] "${name}" did not store the text-plus-media content; ` +
          `got ${JSON.stringify(storedContent)}`,
      );
    }

    const ref: MediaPromptRef = {
      id,
      name,
      version,
      versionLabel: version.versionNumber as string,
      mediaUrls: [MEDIA_PROMPT_IMAGE_URL, MEDIA_PROMPT_VIDEO_URL, MEDIA_PROMPT_AUDIO_URL],
    };

    await testInfo.attach('opik.mediaPrompt', {
      body: JSON.stringify({ ...ref, storedTemplate: stored.template }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[mediaPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './prompt.fixture';

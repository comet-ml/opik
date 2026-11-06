import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { shouldLeaveArtifacts } from '@e2e/core/artifacts';

/**
 * Video and audio attachments open in a playable preview.
 *
 * The preview renders them through `react-player`, a CommonJS package whose
 * default export only survives some bundlers' interop: under Vite 8 the default
 * import arrived as the module object and the dialog crashed into the error
 * boundary. Unit tests resolve the import differently and cannot see that, so
 * only the built app opening a preview can.
 */

/** One second of a 440 Hz tone as 8 kHz, 16-bit mono PCM: a WAV the browser decodes. */
function toneWav(): Buffer<ArrayBuffer> {
  const rate = 8000;
  const samples = rate;
  const wav = Buffer.alloc(44 + samples * 2);
  wav.write('RIFF', 0);
  wav.writeUInt32LE(36 + samples * 2, 4);
  wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(rate, 24);
  wav.writeUInt32LE(rate * 2, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write('data', 36);
  wav.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) {
    wav.writeInt16LE(Math.round(8000 * Math.sin((2 * Math.PI * 440 * i) / rate)), 44 + i * 2);
  }
  return wav;
}

const FILES = [
  // The video's bytes are not a real stream: the regression is the player failing
  // to render at all, which needs no decodable video to show.
  { fileName: 'clip.mp4', mimeType: 'video/mp4', content: Buffer.from('ftyp seeded', 'utf-8'), decodes: false },
  { fileName: 'tone.wav', mimeType: 'audio/wav', content: toneWav(), decodes: true },
];

test.describe('Trace attachment media preview', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'video and audio attachments open in a player, not the error boundary',
    { tag: ['@cap:traces.attachments-media'] },
    async ({ backendClient, opikTrace, project, page }, testInfo) => {
      await test.step('Attach a video and an audio file to the trace', async () => {
        for (const file of FILES) {
          await backendClient.uploadAttachment({
            projectName: project.name,
            entityType: 'trace',
            entityId: opikTrace.id,
            fileName: file.fileName,
            content: file.content,
            mimeType: file.mimeType,
          });
        }
      });

      try {
        const logs = new LogsPage(page);
        const panel = await test.step('Open the trace', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(opikTrace.id);
          await panel.waitForFullyLoaded();
          await panel.openAttachments();
          return panel;
        });

        for (const file of FILES) {
          await test.step(`${file.fileName} previews in a media player`, async () => {
            const dialog = await panel.openAttachmentPreview(file.fileName);
            const player = dialog.locator('video, audio');
            await expect(player, `one media element in the ${file.fileName} preview`).toHaveCount(1);
            await expect(page.getByText('Something went wrong'), 'no error boundary').toHaveCount(0);
            if (file.decodes) {
              // HAVE_METADATA: the player got the file and the browser parsed it.
              await expect
                .poll(() => player.evaluate((el) => (el as HTMLMediaElement).readyState), {
                  message: `readyState of the ${file.fileName} player`,
                })
                .toBeGreaterThanOrEqual(1);
            }
            await page.keyboard.press('Escape');
            await expect(dialog).toBeHidden();
          });
        }
      } finally {
        if (!shouldLeaveArtifacts(testInfo)) {
          await backendClient
            .deleteAttachments({
              projectId: project.id,
              entityType: 'trace',
              entityId: opikTrace.id,
              fileNames: FILES.map((f) => f.fileName),
            })
            .catch((err) => console.warn(`[media preview] delete warning for trace ${opikTrace.id}:`, err));
        }
      }
    },
  );
});

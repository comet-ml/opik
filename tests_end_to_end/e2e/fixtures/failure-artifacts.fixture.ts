import { test as baseTest } from './scratch-dir.fixture';

export interface AttachmentPayload {
  body: Buffer | string;
  contentType: string;
}

export interface ArtifactSource {
  name: string;
  collect(): Promise<AttachmentPayload>;
}

export interface FailureArtifacts {
  capture(source: ArtifactSource): void;
}

export interface FailureArtifactsFixtures {
  failureArtifacts: FailureArtifacts;
}

interface ManifestEntry {
  name: string;
  contentType: string;
  size: number;
  attachedAs: string;
}

export const test = baseTest.extend<FailureArtifactsFixtures>({
  failureArtifacts: async ({}, use, testInfo) => {
    const sources: ArtifactSource[] = [];

    const collector: FailureArtifacts = {
      capture(source) {
        sources.push(source);
      },
    };

    await use(collector);

    if (testInfo.status === 'passed') return;

    const manifest: ManifestEntry[] = [];
    for (const source of sources) {
      const attachmentName = `failure.${source.name}`;
      try {
        const payload = await source.collect();
        await testInfo.attach(attachmentName, {
          body: payload.body,
          contentType: payload.contentType,
        });
        manifest.push({
          name: source.name,
          contentType: payload.contentType,
          size:
            typeof payload.body === 'string'
              ? Buffer.byteLength(payload.body, 'utf-8')
              : payload.body.byteLength,
          attachedAs: attachmentName,
        });
      } catch (err) {
        const errAttachment = `${attachmentName}.error`;
        await testInfo.attach(errAttachment, {
          body: err instanceof Error ? `${err.message}\n${err.stack ?? ''}` : String(err),
          contentType: 'text/plain',
        });
        manifest.push({
          name: source.name,
          contentType: 'error',
          size: 0,
          attachedAs: errAttachment,
        });
      }
    }
    await testInfo.attach('failure-manifest', {
      body: JSON.stringify(manifest, null, 2),
      contentType: 'application/json',
    });
  },
});

export { expect } from './base.fixture';

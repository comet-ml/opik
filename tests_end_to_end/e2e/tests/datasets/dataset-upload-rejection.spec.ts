import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import { startRecordingToasts, readRecordedToasts } from '@e2e/pom/toast-recorder';

/**
 * Malformed dataset uploads are refused synchronously (opik#8605 · OPIK-8188).
 *
 * Before the fix, a CSV with headers differing only in case, a JSON array with a
 * syntax error past its first element, and a JSONL with a non-object after its
 * first line were all answered **202 Accepted**. Validation only ever looked at
 * the head of the file, so the real error surfaced later in async processing —
 * leaving an EMPTY dataset while the UI had already said "is ready to use". That
 * is the data-loss shape worth a permanent test: nothing errors, nothing is
 * logged where a user looks, and the items simply are not there.
 *
 * No spec in the estate uploads a file at all. `datasets.create-dataset-ui` is
 * the Create-dataset sidebar's SDK mode — it types a name and submits — so the
 * whole "Upload a file" path, both endpoints behind it
 * (`/datasets/items/from-csv`, `/datasets/items/from-json`) and all of their
 * synchronous validation were unreached.
 *
 * Every case is asserted on three independent facts, because any one of them
 * alone can be right while the flow is still broken:
 *
 *  1. **The status.** 400 for the three malformed files, 202 for the valid one.
 *     This is the fix itself, and the only place "rejected synchronously" is
 *     observable rather than inferred.
 *  2. **What the user is told.** The error toast carrying the server's own
 *     message, AND — the load-bearing half — NO "is ready to use" toast. The
 *     original bug reported success; a build that 400'd and still raised the
 *     success toast would be just as misleading, and an assertion that only
 *     looked for the error toast would not notice. Toasts are read through the
 *     recorder rather than as live locators because Radix dismisses them after
 *     about five seconds, and a live locator cannot tell "never raised" from
 *     "raised and already gone" — which is exactly the distinction the negative
 *     needs.
 *  3. **What is actually stored.** Zero items for a rejected file, two for the
 *     accepted one. The dataset row exists either way (it is created before the
 *     upload is attempted), so its emptiness is the data loss, stated directly.
 *
 * The valid CSV is the control, and it is not decoration: without it, a build
 * that refused every upload for any reason would satisfy all three rejection
 * assertions perfectly.
 *
 * Fully self-seeding and deterministic — `datasetUploadFiles` writes its own
 * four files into the run's scratch dir, so nothing depends on a checked-in
 * sample, on the workspace's contents or on the wall clock.
 *
 * **Deliberately NOT asserted: the failed-import banner.** The release report's
 * manual item expected a rejected upload to leave the dataset showing "The last
 * file import failed". It does not, and cannot: opik#8605's final commit dropped
 * the synchronous `markFailed` on purpose, because it could overwrite the status
 * of an upload still processing and left healthy datasets FAILED after one bad
 * file. The banner belongs to a row-level failure during ASYNC processing — a
 * JSONL whose `id` is not a UUID — which is a separate flow this spec does not
 * cover.
 */
test.describe(
  'Datasets — a malformed file upload is rejected synchronously',
  { tag: ['@t2-cuj', '@area:datasets'] },
  () => {
    /** Four UI round trips, each with a create, an upload and a read-back. */
    test.setTimeout(300_000);

    /** The toast `useDatasetForm` raises only when the whole create+upload succeeded. */
    const READY_TOAST = /is ready to use/;

    test(
      'each malformed shape answers 400 with its own message, stores nothing, and never claims success',
      { tag: ['@cap:datasets.upload-file-items'] },
      async ({
        datasetUploadFiles,
        project,
        backendClient,
        registerDatasetCleanup,
        page,
      }) => {
        const datasets = new DatasetsPage(page);

        await test.step('Install the toast recorder, then open Datasets', async () => {
          // Before the first navigation: the recorder is an init script, so a
          // page that had already loaded would not be observed. The flow stays
          // inside one document after this — a rejected upload navigates to the
          // dataset's items page through the router, which does not reload — so
          // the recording spans every case below.
          await startRecordingToasts(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
        });

        for (const file of datasetUploadFiles.cases) {
          await test.step(`Upload ${file.baseName}, expecting ${file.expectedStatus}`, async () => {
            // Back to the list between cases: a rejected upload leaves the
            // browser on the previous dataset's items page (the fix opens the
            // dataset instead of raising the success toast), where the create
            // control is a different one.
            await datasets.goto(project.id);
            await datasets.waitForReady();
            await datasets.clickUploadAFile();
            await datasets.attachUploadFile(file.filePath);

            // Read rather than written. The sidebar renders the Name field only
            // after a file passes client validation and pre-fills it from the
            // file's own base name, so this is the name the dataset will get —
            // and asserting it is inside the run prefix is what makes the
            // teardown sweep's claim on it true.
            const datasetName = await datasets.readDerivedUploadName();
            expect(
              datasetName.startsWith(file.baseName),
              `the sidebar derived "${datasetName}" from the file name, so the dataset it ` +
                'creates is inside the swept run namespace',
            ).toBe(true);

            // Subscribed BEFORE the submit: `useDatasetForm` fires the dataset
            // create and the upload back to back inside one handler, so
            // subscribing afterwards races the response this step exists to read.
            const uploaded = page.waitForResponse(
              (response) =>
                response.request().method() === 'POST' &&
                response.url().includes(`/v1/private/datasets/items/${file.endpoint}`),
            );
            const toastsBefore = (await readRecordedToasts(page)).length;
            await datasets.submitUpload();
            const response = await uploaded;

            expect(
              response.status(),
              `${file.key}: the upload is answered ${file.expectedStatus} — this is the whole ` +
                'of "synchronously", and the only place it is observable rather than inferred',
            ).toBe(file.expectedStatus);

            // The dataset row is created before the upload is attempted, so it
            // exists whichever way the upload went. Registered the moment its id
            // is known: teardown then runs even if an assertion below throws,
            // which `global-teardown`'s end-of-run sweep would not do in time to
            // keep the next case's reads clean.
            const dataset = await backendClient.findDatasetByName(datasetName, project.name);
            expect(
              dataset,
              `${file.key}: the dataset "${datasetName}" was created before the upload was ` +
                'attempted, so it exists whether the file was accepted or refused',
            ).not.toBeNull();
            registerDatasetCleanup(dataset!.id, dataset!.name);

            const toasts = (await readRecordedToasts(page)).slice(toastsBefore);
            expect(
              toasts.length,
              `${file.key}: the submit raised at least one toast`,
            ).toBeGreaterThan(0);

            if (file.expectedStatus === 400) {
              // The server's own sentence, reaching the user unaltered: the
              // toast's description is `getApiErrorMessage(error, …)`, so a
              // backend that rejected the file with a precise reason and a
              // frontend that replaced it with "Failed to upload" would be a
              // regression this catches.
              const errorToast = toasts.find((text) =>
                text.includes(`Error uploading ${file.formatLabel} file`),
              );
              expect(
                errorToast,
                `${file.key}: an "Error uploading ${file.formatLabel} file" toast was raised, ` +
                  `out of ${JSON.stringify(toasts)}`,
              ).toBeDefined();
              // Required, not optional-chained: a rejection case with no
              // expected message would mean the fixture and the spec disagreed
              // about which cases are rejections, and coding around that would
              // let the comparison below be skipped in silence.
              expect(
                file.expectedMessage,
                `${file.key}: a rejected case must declare the message it expects`,
              ).toBeDefined();
              expect(
                errorToast!.replace(`Error uploading ${file.formatLabel} file`, '').trim(),
                `${file.key}: the toast carries the server's own message`,
              ).toMatch(file.expectedMessage!);

              // THE negative. The original defect was the UI reporting success
              // over an empty dataset; `onCreateSuccessHandler` now skips the
              // ready toast when the upload was refused. A live locator could
              // not assert this — it cannot distinguish a toast that was never
              // raised from one already dismissed.
              expect(
                toasts.filter((text) => READY_TOAST.test(text)),
                `${file.key}: a refused upload must NOT also tell the user the dataset is ` +
                  'ready — that combination is the data loss the user never notices',
              ).toEqual([]);

              // The other half of the fix's UI behaviour: instead of the success
              // toast, the refused upload opens the dataset it just created.
              await expect
                .poll(() => page.url(), {
                  message: `${file.key}: a refused upload opens the dataset it created`,
                  timeout: 30_000,
                })
                .toContain(`/datasets/${dataset!.id}/items`);
            } else {
              // The control's positive. `useDatasetForm` raises two toasts on
              // this path — "<format> upload accepted" from the upload's
              // `onSuccess`, then "… is ready to use." from `onCreateSuccess` —
              // but only the second is ever observable: `use-toast` has
              // `TOAST_LIMIT = 1`, the two calls land in one React commit, and
              // the newer toast replaces the older before anything is inserted
              // into the DOM. So the assertion is on the toast a user actually
              // sees, and on there being exactly one of it.
              expect(
                toasts.filter((text) => READY_TOAST.test(text)).length,
                `${file.key}: the accepted upload DOES tell the user the dataset is ready — ` +
                  'without this control, the negatives above would also be satisfied by a ' +
                  `build that never raised that toast at all, out of ${JSON.stringify(toasts)}`,
              ).toBe(1);
              expect(
                toasts.filter((text) =>
                  text.includes(`Error uploading ${file.formatLabel} file`),
                ),
                `${file.key}: and raises no upload error beside it`,
              ).toEqual([]);
            }

            // What is actually stored, which is the fact a user cares about and
            // the one the 202-then-fail bug got wrong. Polled, because the
            // accepted file is processed asynchronously by design — and polled to
            // a fixed expectation in both arms, so a rejected upload that
            // nonetheless wrote rows fails here rather than being waited out.
            await expect
              .poll(
                async () => (await backendClient.getDatasetItems(dataset!.id)).length,
                {
                  message:
                    `${file.key}: the dataset settles at ${file.expectedItemCount} items`,
                  timeout: 60_000,
                  intervals: [1_000, 2_000, 5_000],
                },
              )
              .toBe(file.expectedItemCount);
          });
        }
      },
    );
  },
);

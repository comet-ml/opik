import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';
import { SmeFlowPage } from '@e2e/pom/sme-flow.page';

/**
 * A queue's custom annotator view renders sandboxed, falls back to the default
 * view when it cannot render, and cannot put markup of its own on the page
 * (opik#9079, OPIK-8809).
 *
 * `SandboxedView` and the v1 view kit are new shared infrastructure — the
 * annotator view is its first consumer, Ollie's artifacts are the second — and
 * nothing in the estate drove any of it. The backend half is covered
 * (`AnnotationQueuesResourceTest` asserts persist / clear-on-empty /
 * reject-invalid), so what is untested is everything the annotator actually
 * sees.
 *
 * Two of the three outcomes are silent, which is why they are separate tests:
 *
 *  - **A view that cannot render must be REPLACED by the default view.** The
 *    failure mode is a blank frame: the reviewer sees nothing to score, scores
 *    nothing, and the queue looks merely slow. `SandboxedView` gives a view
 *    3 seconds to call `opik.rendered()` and then reports `not_rendered`; the
 *    assertion is that the Input and Output blocks come back WITH an alert
 *    saying why, not that the iframe went away.
 *  - **A view that writes markup must be stopped, not executed.** The frame's
 *    CSP carries `require-trusted-types-for 'script'` with a single `kit`
 *    policy, so `innerHTML` throws; the kit's own `REFERENCE.md` promises that
 *    "trace content can never run as markup". If that promise broke, a trace
 *    whose output happened to contain HTML would render as HTML inside a
 *    reviewer's frame — and nothing would look wrong.
 *
 * ## Why the views are PATCHed rather than authored
 *
 * The dialog offers exactly one authoring path, Ollie, which is an LLM — so a
 * spec that authored its view would be non-deterministic by construction and
 * would also be testing Ollie. `annotator_view` is a plain column behind
 * `PATCH /annotation-queues/{id}`, and what renders is decided by the stored
 * html alone. "Can a human write one" is therefore NOT covered here, and is
 * named as a gap in the exploration rather than papered over.
 *
 * ## Not claimed
 *
 * The create/edit dialog's own annotator-view card (its loader, its error
 * state, and saving without touching the view), the thread surface's
 * `SandboxedAnnotatorThreadView`, the Ollie proposal path, and the preview
 * page. Also not claimed: that the browser logged a CSP violation — the
 * fallback alert is the product's own signal that the view errored, and it is
 * asserted instead of a console string whose wording is the browser's.
 */

/** Marker the markup-writing view tries to inject, and the safe view renders normally. */
const INJECTION_MARKER = 'qa-annotator-view-injection-marker';

/**
 * A view that renders the trace's own fields through the kit, as the reference
 * says to: `kit.mount`, `kit.get` for the payload, `text:` for the content.
 */
const WORKING_VIEW = `<script>
  kit.mount(({ trace }) => kit.h('div', { class: 'kit-stack' },
    kit.card({
      title: 'Question',
      body: kit.h('div', { class: 'kit-text', text: kit.asText(kit.get(trace.input, ['question'])) }),
    }),
    kit.card({
      title: 'Answer',
      body: kit.h('div', { class: 'kit-text', text: kit.asText(kit.get(trace.output, ['answer'])) }),
    }),
  ));
</script>`;

/**
 * A view that never calls `kit.mount` — markup only, no script at all.
 *
 * The reference's own words: "A view that does not call `kit.mount`, throws, or
 * returns nothing is replaced by the default Opik view". This is the first of
 * those three and the only one reachable without writing failing JavaScript,
 * which would also exercise the error path rather than the not-rendered one.
 */
const NEVER_MOUNTS_VIEW = '<div>this view never calls kit.mount</div>';

/**
 * A view that renders the marker the safe way, as the control for the one
 * below: it proves the marker is a string this frame can display, so its
 * absence under the injecting view is the block and not an unrenderable value.
 */
const SAFE_MARKER_VIEW = `<script>
  kit.mount(() => kit.h('div', { class: 'kit-text', text: '${INJECTION_MARKER}' }));
</script>`;

/**
 * A view that tries to write markup into its own document.
 *
 * It calls `kit.mount` too, so a frame where the write SUCCEEDED renders
 * normally and shows the marker — the two outcomes are therefore distinct and
 * neither is "nothing happened". `innerHTML` is the sink the reference names
 * first; the Trusted Types policy makes the assignment throw, the runtime's
 * `window.onerror` reports it, and the view is replaced.
 */
const INJECTING_VIEW = `<div id="qa-sink"></div>
<script>
  document.getElementById('qa-sink').innerHTML = '<b>${INJECTION_MARKER}</b>';
  kit.mount(() => kit.h('div', { class: 'kit-text', text: 'mounted' }));
</script>`;

/** `SandboxedView`'s own render budget is 3s; allow for a cloud round trip on top. */
const FALLBACK_TIMEOUT_MS = 30_000;

test.describe(
  'Annotation queues — the custom annotator view',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'a stored view renders sandboxed, shows the item\'s own fields, and replaces the default blocks',
      { tag: ['@cap:annotation-queues.custom-annotator-view'] },
      async ({ annotatorViewQueue, backendClient, page }) => {
        const queuePage = new AnnotationQueuePage(page);
        const sme = new SmeFlowPage(page);

        await test.step('Store a working view on the queue', async () => {
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: WORKING_VIEW,
            kitVersion: 1,
            summary: 'QA: the trace question and answer as two cards',
          });
          const stored = await backendClient.getAnnotationQueueAnnotatorView(
            annotatorViewQueue.queueId,
          );
          expect(stored, 'the queue stored a view').not.toBeNull();
          expect(stored!.html, 'the html round-trips byte for byte').toBe(WORKING_VIEW);
          expect(stored!.kitVersion, 'the kit version the view was written against').toBe(1);
        });

        await test.step('The queue page says the queue has a custom view', async () => {
          await queuePage.goto(annotatorViewQueue.projectId, annotatorViewQueue.queueId);
          await queuePage.waitForReady();
          await expect(
            queuePage.customAnnotatorViewTag,
            'the only place outside the SME flow that tells an operator this queue shows ' +
              'annotators something other than the default view',
          ).toBeVisible();
        });

        await test.step('The SME flow renders the view inside the sandboxed frame', async () => {
          await sme.goto(annotatorViewQueue.queueId);
          await expect(sme.welcomeHeading(annotatorViewQueue.queueName)).toBeVisible({
            timeout: 30_000,
          });
          await sme.startAnnotating();
          await expect(
            sme.annotatorViewFrame,
            'exactly one sandboxed annotator frame',
          ).toHaveCount(1, { timeout: FALLBACK_TIMEOUT_MS });
          // The sandbox is the security contract, not a detail: without
          // `allow-scripts` alone the frame would be same-origin with the app
          // and the CSP in its own document would be the only thing between a
          // stored view and the session.
          await expect(sme.annotatorViewFrame).toHaveAttribute('sandbox', 'allow-scripts');
        });

        await test.step('It renders THIS item\'s own question and answer', async () => {
          // Read inside the frame. Both strings carry the test namespace, so
          // they appear nowhere else in the workspace — a view handed the wrong
          // trace, or handed nothing, renders neither.
          await expect(
            sme.annotatorViewCardHeader('Question'),
            'the view\'s first card',
          ).toHaveCount(1, { timeout: FALLBACK_TIMEOUT_MS });
          await expect(sme.annotatorViewCardHeader('Answer'), 'the view\'s second card').toHaveCount(
            1,
          );
          await expect(
            sme.annotatorViewTextBlocks,
            'the view rendered the question and the answer, and nothing else',
          ).toHaveText([annotatorViewQueue.question, annotatorViewQueue.answer], {
            timeout: FALLBACK_TIMEOUT_MS,
          });
        });

        await test.step('The default view is replaced, not merely hidden behind it', async () => {
          // `TraceDataViewer` renders either the sandboxed view OR
          // `DefaultTraceView`, never both — so a page carrying the Input block
          // as well would mean the custom view had failed and fallen back while
          // still showing a frame.
          await expect(
            sme.codeBlock('Input'),
            'the default view\'s Input block is gone while the custom view is up',
          ).toHaveCount(0);
          await expect(
            sme.annotatorViewFallbackAlert,
            'a view that rendered raises no fallback alert',
          ).toHaveCount(0);
        });
      },
    );

    test(
      'a view that never renders is replaced by the default view, with an alert saying so',
      { tag: ['@cap:annotation-queues.custom-annotator-view'] },
      async ({ annotatorViewQueue, backendClient, page }) => {
        const sme = new SmeFlowPage(page);

        await test.step('Store a view that never calls kit.mount', async () => {
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: NEVER_MOUNTS_VIEW,
            kitVersion: 1,
          });
          const stored = await backendClient.getAnnotationQueueAnnotatorView(
            annotatorViewQueue.queueId,
          );
          // Asserted, because the whole test rests on the queue really carrying
          // a view: a PATCH the backend had rejected would leave the default
          // view on screen for the ordinary reason, and every assertion below
          // would pass having tested nothing.
          expect(stored?.html, 'the broken view was stored').toBe(NEVER_MOUNTS_VIEW);
        });

        await test.step('Open the SME flow', async () => {
          await sme.goto(annotatorViewQueue.queueId);
          await expect(sme.welcomeHeading(annotatorViewQueue.queueName)).toBeVisible({
            timeout: 30_000,
          });
          await sme.startAnnotating();
        });

        await test.step('The annotator is told the view failed, in words', async () => {
          await expect(
            sme.annotatorViewFallbackAlert,
            'the reviewer is told the custom view failed and what they are looking at instead',
          ).toBeVisible({ timeout: FALLBACK_TIMEOUT_MS });
        });

        await test.step('And is given the default view, not a blank frame', async () => {
          // The half that matters. An alert over an empty pane is the same
          // dead end as a blank frame: there is still nothing to score.
          await sme.waitForTraceReady();
          await expect(
            sme.codeBlock('Input'),
            'the default view\'s Input block carries the trace\'s own question',
          ).toContainText(annotatorViewQueue.question);
          await expect(
            sme.codeBlock('Output'),
            'and its Output block the answer',
          ).toContainText(annotatorViewQueue.answer);
          await expect(
            sme.annotatorViewFrame,
            'the frame that could not render is gone rather than sitting there empty',
          ).toHaveCount(0);
        });
      },
    );

    test(
      'a view that writes markup is stopped and replaced, and its markup never reaches the page',
      { tag: ['@cap:annotation-queues.custom-annotator-view'] },
      async ({ annotatorViewQueue, backendClient, page }) => {
        const sme = new SmeFlowPage(page);

        await test.step('A view that renders the marker the SAFE way shows it (control)', async () => {
          // Establishes that the marker is a string this frame can display, so
          // its absence below is the write being blocked rather than the value
          // being unrenderable or the locator never matching anything.
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: SAFE_MARKER_VIEW,
            kitVersion: 1,
          });
          await sme.goto(annotatorViewQueue.queueId);
          await expect(sme.welcomeHeading(annotatorViewQueue.queueName)).toBeVisible({
            timeout: 30_000,
          });
          await sme.startAnnotating();
          await expect(
            sme.annotatorViewTextBlocks,
            'the marker renders when the view sets it as TEXT',
          ).toHaveText([INJECTION_MARKER], { timeout: FALLBACK_TIMEOUT_MS });
        });

        await test.step('Store the same marker, written as markup instead', async () => {
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: INJECTING_VIEW,
            kitVersion: 1,
          });
          expect(
            (await backendClient.getAnnotationQueueAnnotatorView(annotatorViewQueue.queueId))?.html,
            'the markup-writing view was stored — the backend does not vet the html, the frame does',
          ).toBe(INJECTING_VIEW);
        });

        await test.step('The write is refused and the view is replaced', async () => {
          await sme.goto(annotatorViewQueue.queueId);
          await expect(sme.startAnnotatingButton).toHaveCount(1, { timeout: 30_000 });
          await sme.startAnnotating();
          await expect(
            sme.annotatorViewFallbackAlert,
            'the markup write threw inside the frame, so the view is reported failed',
          ).toBeVisible({ timeout: FALLBACK_TIMEOUT_MS });
        });

        await test.step('The markup it tried to write is nowhere on the page', async () => {
          // Both halves: the written text never appears, and neither does the
          // element it would have created. The step above is what makes this
          // discriminating — the same marker rendered a moment ago under the
          // safe view, and this view DID call `kit.mount`, so "nothing
          // rendered" cannot be explained by the view simply doing nothing.
          await expect(
            page.getByText(INJECTION_MARKER),
            'the markup a stored view tried to write must never reach the annotator',
          ).toHaveCount(0);
          await expect(
            page.locator('#qa-sink'),
            'and neither must the element it wrote into',
          ).toHaveCount(0);
        });

        await test.step('The annotator still has the default view to work from', async () => {
          await sme.waitForTraceReady();
          await expect(sme.codeBlock('Input')).toContainText(annotatorViewQueue.question);
        });
      },
    );

    test(
      'clearing a view with empty html reads back as no view at all, and the queue loses its tag',
      { tag: ['@cap:annotation-queues.custom-annotator-view'] },
      async ({ annotatorViewQueue, backendClient, page }) => {
        const queuePage = new AnnotationQueuePage(page);
        const sme = new SmeFlowPage(page);

        await test.step('The queue carries a working view to begin with', async () => {
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: WORKING_VIEW,
            kitVersion: 1,
          });
          await queuePage.goto(annotatorViewQueue.projectId, annotatorViewQueue.queueId);
          await queuePage.waitForReady();
          await expect(
            queuePage.customAnnotatorViewTag,
            'the starting state this test is about leaving',
          ).toBeVisible();
        });

        await test.step('Clearing it stores nothing rather than an empty view', async () => {
          // There is no delete verb: the backend's `serializeAnnotatorView`
          // writes null for blank html. So "an empty view" must not be a state
          // the queue can be in — a queue storing `{html: ""}` would keep its
          // tag and hand the SME flow an empty frame forever.
          await backendClient.setAnnotationQueueAnnotatorView(annotatorViewQueue.queueId, {
            html: '',
          });
          expect(
            await backendClient.getAnnotationQueueAnnotatorView(annotatorViewQueue.queueId),
            'the queue reads back with no annotator view at all',
          ).toBeNull();
        });

        await test.step('The queue page drops the tag', async () => {
          await page.reload();
          await queuePage.waitForReady();
          await expect(
            queuePage.customAnnotatorViewTag,
            'a queue with no stored view must not advertise one',
          ).toHaveCount(0);
        });

        await test.step('And the SME flow is back on the default view, with no alert', async () => {
          await sme.goto(annotatorViewQueue.queueId);
          await expect(sme.welcomeHeading(annotatorViewQueue.queueName)).toBeVisible({
            timeout: 30_000,
          });
          await sme.startAnnotating();
          await sme.waitForTraceReady();
          await expect(sme.annotatorViewFrame, 'no sandboxed frame is mounted').toHaveCount(0);
          // A cleared view is not a failed one: the alert would tell the
          // reviewer something went wrong when nothing did.
          await expect(
            sme.annotatorViewFallbackAlert,
            'clearing a view is not a failure, so no alert is raised',
          ).toHaveCount(0);
          await expect(sme.codeBlock('Input')).toContainText(annotatorViewQueue.question);
        });
      },
    );
  },
);

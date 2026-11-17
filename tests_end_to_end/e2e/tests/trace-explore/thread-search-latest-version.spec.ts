import { test, expect } from '@e2e/fixtures';
import { numericStat } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Thread free-text search reads each trace's LATEST version (opik#9264 · OPIK-9023).
 *
 * This is the semantic 9264 actually introduced. The PR's headline is a memory
 * fix — the `ilike` moved out from under `FINAL`, which held every part's
 * payload blocks at once and ran a project of multi-MiB messages past the query
 * memory limit — but the way it got there changed the answer the search gives:
 * a key-only `(id, max(last_updated_at))` pass now picks each trace's version
 * and the `ilike` reads only those rows. Which version is matched is therefore
 * a decision the query makes explicitly, where before it fell out of FINAL's
 * merge.
 *
 * Nothing in the estate could tell the two apart. `thread-free-text-search.spec.ts`
 * seeds every trace once and never updates one, so a clause matching the FIRST
 * stored version and one matching the LATEST give identical answers over its
 * whole 12-thread population. An UPDATED trace is the only seed that separates
 * them, and no thread spec writes one.
 *
 * The failure mode is the quiet one. A user edits a conversation — or an agent
 * PATCHes a trace as it streams, which is the ordinary path, not an exotic
 * one — and the conversation stops coming back for the text it now contains, or
 * keeps coming back for text it no longer contains. There is no error: the
 * thread is simply not in the list, and the rest of the table looks perfectly
 * healthy.
 *
 * So the subject is a CHANGE of answer, asserted in both directions over the
 * same seed:
 *
 *  - the `gains` thread is PATCHed INTO carrying the marker, and must appear;
 *  - the `loses` thread is PATCHed OUT of carrying it, and must disappear.
 *
 * Either half alone is satisfied by a wrong build. A clause reading every
 * version ever stored passes the first and fails the second; a clause stuck on
 * the first version fails the first and passes the second. Only the pair pins
 * "the latest version, and only the latest" — which is why the BEFORE answer is
 * asserted too, rather than only the after.
 */
test.describe(
  'Threads — free-text search matches each trace\'s latest version',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** A batch write, a thread-aggregation poll, two updates, then several reads. */
    test.setTimeout(300_000);

    /**
     * The date-range preset the UI half runs under.
     *
     * Bounded rather than `alltime` because `ThreadsTab` excludes the all-time
     * preset, so the Threads read is always windowed. The whole seed is minutes
     * old, so a 24-hour window holds all of it — which the spec asserts as its
     * unsearched baseline before trusting any searched comparison.
     */
    const TIME_RANGE = 'past24hours';

    /**
     * How long a PATCHed payload may take to become the version the search
     * reads.
     *
     * A trace update is persisted as a new row rather than an edit in place, so
     * the read has to see that row and then rank it latest. Polled rather than
     * waited out: a fixed sleep either flakes or wastes the difference.
     */
    const UPDATE_VISIBLE_TIMEOUT_MS = 120_000;

    const sorted = (ids: string[]): string[] => [...ids].sort();

    test(
      'a trace updated into a match appears and one updated out of it disappears',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadVersionSearch, project, backendClient }) => {
        const seed = threadVersionSearch;
        const gains = seed.byRole('gains');
        const loses = seed.byRole('loses');
        const decoy = seed.byRole('decoy');

        const readIds = async (search?: string) => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            size: 200,
            ...(search === undefined ? {} : { search }),
          });
          return { ids: sorted(answer.threads.map((t) => t.id)), total: answer.total };
        };

        const threadCount = async (search?: string) => {
          const stats = await backendClient.getThreadsStats({
            projectId: project.id,
            ...(search === undefined ? {} : { search }),
          });
          // `numericStat` asserts the stat is PRESENT before narrowing it: the
          // endpoint answers an empty stats list rather than a zeroed count
          // when it matches nothing, so a reader defaulting a missing
          // `thread_count` to 0 would turn "the server reported nothing" into a
          // passing number.
          return numericStat(stats.thread_count, 'thread_count');
        };

        await test.step('With no search, the project serves every seeded thread', async () => {
          const answer = await readIds();
          // The baseline every comparison below rests on. If the unsearched read
          // were already short, a searched read agreeing with an expectation
          // would mean nothing.
          expect(answer.total, 'the unsearched listing reports the whole population').toBe(
            seed.allThreadIds.length,
          );
          expect(answer.ids, 'and serves exactly the seeded threads').toEqual(seed.allThreadIds);
        });

        await test.step(`BEFORE any update, "${seed.marker}" matches only the thread seeded with it`, async () => {
          // The half that makes the "after" assertion mean something. Without
          // it, a search that returned the `gains` thread later could be a
          // clause that had always returned it — or one that returns everything
          // — and nothing here would notice.
          const answer = await readIds(seed.marker);
          expect(
            answer.ids,
            `'${loses.threadId}' was seeded carrying the marker and is the only match`,
          ).toEqual(seed.threadIdsMatchingBeforeUpdate);
          expect(answer.total, 'and the envelope agrees that one thread matched').toBe(1);
          expect(
            await threadCount(seed.marker),
            'the count card agrees with the listing before the update',
          ).toBe(1);
        });

        await test.step('PATCH both traces: one into the marker, one out of it', async () => {
          // Both payload columns in each update, which is the point rather than
          // thoroughness: the clause reads the raw latest VERSION of a trace,
          // not the merged row a by-id read answers, so an update mentioning one
          // column would leave the other absent from the row being matched —
          // and "the search reads the latest version" would be
          // indistinguishable from "the search lost a column".
          const gainsPayload = seed.updatedPayload('gains');
          await backendClient.updateTracePayload({
            traceId: gains.traceId,
            projectName: project.name,
            input: gainsPayload.input,
            output: gainsPayload.output,
          });

          const losesPayload = seed.updatedPayload('loses');
          await backendClient.updateTracePayload({
            traceId: loses.traceId,
            projectName: project.name,
            input: losesPayload.input,
            output: losesPayload.output,
          });
        });

        await test.step('The update is readable before anything is concluded from it', async () => {
          // Polled on the `gains` direction alone, deliberately. It is the only
          // one of the two whose "not yet" and whose "wrong" look different: a
          // thread that has not appeared yet and a thread that will never
          // appear are both absent, but the thread is at least expected to
          // arrive, so a poll resolves rather than masks. Polling the `loses`
          // direction instead would retry the exact shortfall this spec exists
          // to detect and report it as a timeout.
          await expect
            .poll(async () => (await readIds(seed.marker)).ids.includes(gains.threadId), {
              message:
                `the PATCHed '${gains.traceId}' must become the version the search reads ` +
                'before the answer is asserted',
              timeout: UPDATE_VISIBLE_TIMEOUT_MS,
              intervals: [2_000, 2_000, 5_000],
            })
            .toBe(true);
        });

        await test.step(`AFTER the updates, "${seed.marker}" matches exactly the thread that gained it`, async () => {
          const answer = await readIds(seed.marker);
          // As a set, not as membership. `toEqual` here is simultaneously the
          // three facts worth having: the gained thread is in, the thread that
          // lost the marker is OUT — the half a search reading a stale version
          // gets wrong — and the decoy never came.
          expect(
            answer.ids,
            `exactly '${gains.threadId}': '${loses.threadId}' no longer carries the marker ` +
              'in its latest version, so a stale match would show up here',
          ).toEqual(seed.threadIdsMatchingAfterUpdate);
          expect(answer.total, 'and the envelope reports exactly one').toBe(1);
          expect(
            await threadCount(seed.marker),
            'the count card agrees with the listing after the update',
          ).toBe(1);
        });

        await test.step(`The thread that lost the marker is findable by its NEW text`, async () => {
          // "The old term stopped matching" and "the trace fell out of the
          // index" are different outcomes and only this tells them apart. A
          // build that dropped the updated version entirely would satisfy every
          // assertion above and fail here.
          const answer = await readIds(seed.losesNewToken);
          expect(
            answer.ids,
            `'${seed.losesNewToken}' is the text '${loses.threadId}' now carries, so it must ` +
              'come back for it',
          ).toEqual([loses.threadId]);
          expect(answer.total, 'and the envelope reports exactly one').toBe(1);
        });

        await test.step('The thread that gained the marker is no longer findable by its OLD text', async () => {
          // The mirror, and the one assertion that can only fail on a clause
          // reading EVERY version rather than the latest: the `gains` thread's
          // first version carried this text and its current one does not.
          const answer = await readIds(seed.gainsOldPhrase);
          expect(
            answer.ids,
            `'${gains.threadId}' was PATCHed away from that text, and the superseded version ` +
              'must not be matched',
          ).toEqual([]);
          expect(answer.total, 'and the envelope reports none').toBe(0);
        });

        await test.step('The untouched decoy never matched anything, and still does not', async () => {
          // The bystander. Both updates are writes against this project, and
          // asserting only on the two rows they name would pass equally well
          // against an update that had disturbed the whole project's index.
          const answer = await readIds(decoy.token);
          expect(
            answer.ids,
            `the never-updated '${decoy.threadId}' is still findable by its own token`,
          ).toEqual([decoy.threadId]);
          const whole = await readIds();
          expect(
            whole.ids,
            'and the project still holds every seeded thread — an update rewrote versions, ' +
              'not the population',
          ).toEqual(seed.allThreadIds);
        });
      },
    );

    test(
      'the Threads table renders the post-update answer the API gives for the same term',
      { tag: ['@cap:threads.free-text-search'] },
      async ({ threadVersionSearch, project, backendClient, page }) => {
        const seed = threadVersionSearch;
        const gains = seed.byRole('gains');
        const loses = seed.byRole('loses');
        const logs = new LogsPage(page);

        // Its own test rather than more steps above, and not a second copy of
        // those assertions: the box and the read are wired independently — the
        // input commits `threads_search` and `useThreadList` turns that into the
        // `search` param — so what is checked here is the AGREEMENT between what
        // the page shows and what the API says for the identical term, over a
        // population whose latest versions are not its seeded ones.
        await test.step('PATCH both traces, as the API half does', async () => {
          const gainsPayload = seed.updatedPayload('gains');
          await backendClient.updateTracePayload({
            traceId: gains.traceId,
            projectName: project.name,
            input: gainsPayload.input,
            output: gainsPayload.output,
          });
          const losesPayload = seed.updatedPayload('loses');
          await backendClient.updateTracePayload({
            traceId: loses.traceId,
            projectName: project.name,
            input: losesPayload.input,
            output: losesPayload.output,
          });
        });

        const apiAnswer = await test.step('Wait for the update, then ask the API', async () => {
          await expect
            .poll(
              async () => {
                const answer = await backendClient.listThreads({
                  projectId: project.id,
                  search: seed.marker,
                  size: 200,
                });
                return sorted(answer.threads.map((t) => t.id)).join(',');
              },
              {
                message: 'the searched API answer must settle on the updated versions',
                timeout: UPDATE_VISIBLE_TIMEOUT_MS,
                intervals: [2_000, 2_000, 5_000],
              },
            )
            .toBe(seed.threadIdsMatchingAfterUpdate.join(','));

          const answer = await backendClient.listThreads({
            projectId: project.id,
            search: seed.marker,
            size: 200,
          });
          // Asserted here as well, because this test's subject is the agreement
          // between the two: if the API answer were itself wrong, "the table
          // agrees with the API" would be satisfied by two wrong answers.
          expect(
            sorted(answer.threads.map((t) => t.id)),
            'the API serves exactly the thread that gained the marker',
          ).toEqual(seed.threadIdsMatchingAfterUpdate);
          return answer;
        });

        await test.step('Open the project Logs on the Threads tab, whole seed on one page', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          // A longer readiness window than the 15s default: the fixture has
          // already waited for every thread to be aggregated server-side, but
          // the first paint of a cold Logs page over a freshly seeded project on
          // a shared cloud workspace has been seen to exceed it.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
        });

        await test.step('Before searching, the table shows the whole seeded project', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports the whole seeded population',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          // Also the proof that the 24-hour window is not cutting the seed:
          // every later comparison would be meaningless over a baseline that
          // was already short.
          await expect(logs.threadRows, 'the table body paints every row').toHaveCount(
            seed.allThreadIds.length,
          );
          expect(
            sorted(await logs.readThreadIdsOnPage()),
            'and renders exactly the seeded threads',
          ).toEqual(seed.allThreadIds);
        });

        await test.step(`Type "${seed.marker}" into Search by anything`, async () => {
          // Already lower-case and trimmed, deliberately: `ThreadsTab` passes
          // the RAW term to the stats read and the trimmed, folded one to the
          // listing, so a term needing either treatment would have the table and
          // its count card asking different questions by construction.
          await logs.searchFor(seed.marker, 'threads');
        });

        await test.step('The footer, the rendered rows and the API all say the same thing', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer narrows to the searched population',
              timeout: 60_000,
            })
            .toBe(apiAnswer.total);
          const rendered = await logs.readThreadIdsOnPage();
          expect(
            sorted(rendered),
            'the table renders exactly the rows the API returned for this term',
          ).toEqual(sorted(apiAnswer.threads.map((t) => t.id)));
          expect(
            sorted(rendered),
            'which is the thread whose latest version gained the marker',
          ).toEqual(seed.threadIdsMatchingAfterUpdate);
        });

        await test.step('The thread that lost the marker is not on screen', async () => {
          // Stated as the negative too. The set comparison above implies it, but
          // a stale row left in the DOM outside the table's row set — a
          // duplicated body, a render that did not repaint — would pass that and
          // fail this. And it is the specific row whose absence the whole spec
          // is about.
          await expect(
            logs.threadRow(loses.threadId),
            `'${loses.threadId}' no longer carries the marker and must not be rendered`,
          ).toHaveCount(0);
        });

        await test.step('Clearing the search restores the whole project', async () => {
          await logs.clearSearch('threads');
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer returns to the whole population',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          expect(
            sorted(await logs.readThreadIdsOnPage()),
            'every seeded thread is back, so the search narrowed rather than deleted',
          ).toEqual(seed.allThreadIds);
        });
      },
    );
  },
);

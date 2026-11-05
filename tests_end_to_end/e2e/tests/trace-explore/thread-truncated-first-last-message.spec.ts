import { test, expect } from '@e2e/fixtures';
import type { TruncatedThreadRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A thread row's First and Last message come from its FIRST and LAST trace even
 * when those payloads are truncated (opik#9314, OPIK-9058).
 *
 * On the threads-list page-pushdown read, `traces_final` no longer selects any
 * payload at all. `thread_message_traces` picks each thread's first and last
 * TRACE from the narrow columns (`argMinIf((id, id_week), (start_time, id), …)`
 * / `argMaxIf(…, (end_time, id), …)`), and `thread_messages` then reads
 * `input`, `output`, `truncated_input`, `truncated_output`, `input_length`,
 * `output_length` and `truncation_threshold` for only those two traces, outside
 * FINAL, keeping each id's latest version. Read under FINAL for every trace of
 * the page's threads, those columns ran a project with multi-MiB messages past
 * the query memory limit.
 *
 * So every one of the three truncation columns now arrives from a different
 * subquery than it did, and they are the columns that decide the SHAPE of the
 * answer: `first_message_truncated` is rendered as `first_message_length >=
 * first_message_truncation_threshold`, and `TruncationUtils.getJsonNodeOrTruncatedString`
 * turns a truncated value into a `TextNode` — a JSON *string* holding the capped
 * prefix — where an untruncated one is parsed into an object.
 *
 * ## Why nothing in the estate catches this
 *
 * `thread-logging-smoke.spec.ts` (`@t1-smoke`) and
 * `thread-search-multi-turn-aggregate.spec.ts` (`@t2-cuj`) both assert exactly
 * these two cells, and both reach the changed branch — but both seed SHORT
 * messages, so the truncated columns are dead in them and `first_message` has
 * only ever been the untruncated one. `thread-moved-trace-aggregate.spec.ts`
 * does drive both truncate paths, but through `POST /threads/retrieve`, which is
 * a separately written query 9314 did not touch. The gap is the intersection:
 * the LIST query, on the pushdown branch, with a payload over the threshold.
 *
 * ## Why the answer is a finding rather than a crash
 *
 * A cell taken from the wrong turn renders as a perfectly ordinary row. So the
 * fixture marks every payload with the thread, turn and side it belongs to, puts
 * the over-threshold payload on turn 1's input in one conversation and on turn
 * 3's output in another, and keeps a third entirely short as the control — and
 * the assertions below name the turn a wrong answer would have come from rather
 * than only the turn it should.
 *
 * Both surfaces, because the candidate is `both`: the API is where the columns
 * are chosen, the Threads table is where a user reads them, and the table asks
 * for `truncate=true` (`ThreadsTab` passes the workspace's
 * `truncation_on_tables`, default on) — so a disagreement between the two is its
 * own bug.
 */
test.describe(
  'Threads — first and last message with a truncated payload',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    /** Two batch writes, an aggregation poll, three API reads and a UI pass. */
    test.setTimeout(300_000);

    /** Bounded, because `ThreadsTab` excludes the `alltime` preset. */
    const TIME_RANGE = 'past24hours';

    /**
     * The markers a thread's First message must NOT carry — every input marker
     * of the conversation except turn 1's, plus every output marker.
     *
     * Computed from the seed rather than listed, so a fixture that grew a fourth
     * turn is covered without this spec being edited into agreement with it.
     */
    const forbiddenInFirst = (thread: TruncatedThreadRef): string[] => [
      ...thread.turns.filter((t) => t.turn !== 1).map((t) => t.inputMarker),
      ...thread.turns.map((t) => t.outputMarker),
    ];

    /** The mirror: everything except the LAST turn's output marker. */
    const forbiddenInLast = (thread: TruncatedThreadRef): string[] => {
      const last = thread.turns[thread.turns.length - 1].turn;
      return [
        ...thread.turns.map((t) => t.inputMarker),
        ...thread.turns.filter((t) => t.turn !== last).map((t) => t.outputMarker),
      ];
    };

    const firstMarkerOf = (thread: TruncatedThreadRef): string => thread.turns[0].inputMarker;
    const lastMarkerOf = (thread: TruncatedThreadRef): string =>
      thread.turns[thread.turns.length - 1].outputMarker;

    test(
      'both query branches answer each thread\'s own first input and last output, truncated or not',
      { tag: ['@cap:threads.turn-order-io'] },
      async ({ truncatedThreadMessages, project, backendClient }) => {
        const seed = truncatedThreadMessages;

        /**
         * Read the page the Threads table reads and return the rows by thread
         * id, having first asserted the answer is the WHOLE seeded population.
         *
         * The population matters as much as the rows: a read that also carried
         * a thread it should not have would satisfy a per-thread lookup, and a
         * read that lost one would make a `for` loop over what came back assert
         * nothing about the missing conversation.
         */
        const rowsByThread = async (
          what: string,
          args: { truncate?: boolean; sorting?: { field: string; direction: 'ASC' | 'DESC' }[] },
        ) => {
          const answer = await backendClient.listThreads({
            projectId: project.id,
            size: 100,
            ...args,
          });
          expect(
            answer.threads.map((t) => t.id).sort(),
            `${what} lists exactly the three seeded conversations`,
          ).toEqual(seed.allThreadIds);
          expect(answer.total, `${what} reports all three as the population`).toBe(
            seed.allThreadIds.length,
          );
          return { answer, byId: new Map(answer.threads.map((t) => [t.id, t])) };
        };

        const pushdown = await test.step(
          'The default read — no filter, no sort — takes the page-pushdown branch',
          async () => {
            // Nothing that `PAGE_PUSHDOWN_DISQUALIFIERS` or
            // `PAGE_PUSHDOWN_SORT_FIELDS` would reject, which is what makes this
            // the branch 9314 rewrote. `truncate: true` is what the table asks
            // for, and the only way to reach the truncation columns at all.
            const { byId } = await rowsByThread('the default truncated read', {
              truncate: true,
            });
            return byId;
          },
        );

        await test.step('Each row opens on its own turn 1 and closes on its own turn 3', async () => {
          for (const thread of seed.threads) {
            const row = pushdown.get(thread.threadId)!;
            const first = JSON.stringify(row.firstMessage);
            const last = JSON.stringify(row.lastMessage);

            expect(
              first,
              `${thread.key}: First message is turn 1's INPUT`,
            ).toContain(firstMarkerOf(thread));
            for (const forbidden of forbiddenInFirst(thread)) {
              // Named individually: a wrong answer here says WHICH turn and
              // which side it came from, which is the difference between a
              // reportable finding and "not what we expected".
              expect(
                first,
                `${thread.key}: First message must not be ${forbidden}`,
              ).not.toContain(forbidden);
            }

            expect(
              last,
              `${thread.key}: Last message is the LAST turn's OUTPUT`,
            ).toContain(lastMarkerOf(thread));
            for (const forbidden of forbiddenInLast(thread)) {
              expect(
                last,
                `${thread.key}: Last message must not be ${forbidden}`,
              ).not.toContain(forbidden);
            }
          }
        });

        await test.step(
          `A payload at or over ${seed.threshold} characters comes back as a capped JSON string, a shorter one as an object`,
          async () => {
            for (const thread of seed.threads) {
              const row = pushdown.get(thread.threadId)!;
              for (const [side, value, truncated, seeded] of [
                [
                  'First',
                  row.firstMessage,
                  thread.firstMessageTruncated,
                  thread.turns[0].inputLength,
                ],
                [
                  'Last',
                  row.lastMessage,
                  thread.lastMessageTruncated,
                  thread.turns[thread.turns.length - 1].outputLength,
                ],
              ] as const) {
                if (truncated) {
                  // The type flip IS the truncation signal on this endpoint —
                  // there is no `*_truncated` field on the response — so it is
                  // asserted as the contract rather than inferred from length.
                  expect(
                    typeof value,
                    `${thread.key}: the truncated ${side} message is served as a JSON string`,
                  ).toBe('string');
                  expect(
                    (value as string).length,
                    `${thread.key}: and cut to exactly the ${seed.threshold}-character threshold`,
                  ).toBe(seed.threshold);
                  // The cut really removed something, stated against the length
                  // the seed wrote: a threshold that had grown past the payload
                  // would leave the two equal and the assertion above would be
                  // about an untruncated value.
                  expect(
                    seeded,
                    `${thread.key}: and the seeded ${side} payload was longer than the cut`,
                  ).toBeGreaterThan(seed.threshold);
                } else {
                  expect(
                    typeof value,
                    `${thread.key}: the ${side} message is under the threshold, so it is parsed`,
                  ).toBe('object');
                  expect(
                    value,
                    `${thread.key}: and is not null — an absent aggregate is a regression, ` +
                      'not an untruncated value',
                  ).not.toBeNull();
                }
              }
            }
          },
        );

        await test.step('Asked untruncated, the same read serves the payloads whole', async () => {
          // The other half of the type flip, and the proof that truncation is a
          // display concern rather than data loss: `truncate: false` selects
          // `t.first_message`/`t.last_message` instead of the `truncated_*`
          // pair, so every row comes back parsed and the over-long ones come
          // back at their full seeded length.
          const { byId } = await rowsByThread('the untruncated read', { truncate: false });
          for (const thread of seed.threads) {
            const row = byId.get(thread.threadId)!;
            expect(
              typeof row.firstMessage,
              `${thread.key}: untruncated, First message is an object whatever its length`,
            ).toBe('object');
            expect(
              typeof row.lastMessage,
              `${thread.key}: untruncated, Last message is an object too`,
            ).toBe('object');
            expect(
              JSON.stringify(row.firstMessage).length,
              `${thread.key}: and First message is the whole payload the seed wrote`,
            ).toBe(thread.turns[0].inputLength);
            expect(
              JSON.stringify(row.lastMessage).length,
              `${thread.key}: as is Last message`,
            ).toBe(thread.turns[thread.turns.length - 1].outputLength);
            // Still the right turn, so "whole" is not being satisfied by the
            // wrong trace's whole payload.
            expect(
              JSON.stringify(row.firstMessage),
              `${thread.key}: untruncated, First message is still turn 1's input`,
            ).toContain(firstMarkerOf(thread));
            expect(
              JSON.stringify(row.lastMessage),
              `${thread.key}: untruncated, Last message is still the last turn's output`,
            ).toContain(lastMarkerOf(thread));
          }
        });

        await test.step('The full-scan copy of the query answers identically', async () => {
          // `total_estimated_cost` is not in `PAGE_PUSHDOWN_SORT_FIELDS`, so a
          // cost sort takes the read off the id-only pre-pass and down the full
          // scan — the copy 9314 deliberately left on main's SQL, because there
          // `traces_final` spans every thread of the project and the second
          // narrow pass costs more than the payload it saves. The two branches
          // now compute these columns in completely different ways, so them
          // agreeing is the claim; a rewrite that answered one turn later on one
          // branch would show up here and nowhere else.
          const { answer, byId } = await rowsByThread('the cost-sorted read', {
            truncate: true,
            sorting: [{ field: 'total_estimated_cost', direction: 'DESC' }],
          });
          // That the sort was honoured, so the read really did leave the
          // pushdown: an ignored `sorting` would take the SAME branch and make
          // the comparison below a comparison of one query with itself.
          expect(
            answer.threads.map((t) => t.id),
            'the cost sort ordered the page by each conversation\'s summed cost, descending',
          ).toEqual(
            [...seed.threads]
              .sort((a, b) => b.totalEstimatedCost - a.totalEstimatedCost)
              .map((t) => t.threadId),
          );

          for (const thread of seed.threads) {
            const scan = byId.get(thread.threadId)!;
            const page = pushdown.get(thread.threadId)!;
            expect(
              { first: scan.firstMessage, last: scan.lastMessage },
              `${thread.key}: the full-scan copy answers the pushdown copy's messages exactly`,
            ).toEqual({ first: page.firstMessage, last: page.lastMessage });
          }
        });
      },
    );

    test(
      'the Threads table renders each row\'s own first input and last output',
      { tag: ['@cap:threads.turn-order-io'] },
      async ({ truncatedThreadMessages, project, backendClient, page }) => {
        const seed = truncatedThreadMessages;
        const logs = new LogsPage(page);

        /**
         * Every threads-list URL the page requested, collected from the first
         * navigation onwards.
         *
         * The table's `truncate` is not a constant this spec can assume: it is
         * `truncation_on_tables` from `/v1/private/...is-alive/ver` via
         * `useServerSync`, defaulting to on. Asserting the request was made with
         * `truncate=true` is what makes this test a test of the TRUNCATED
         * branch rather than of whatever the workspace happens to be set to.
         */
        const threadRequests: string[] = [];
        page.on('request', (request) => {
          const url = request.url();
          if (/\/v1\/private\/traces\/threads\?/.test(url)) threadRequests.push(url);
        });

        const apiRows = await test.step(
          'Ask the API, as the table will, what each row should show',
          async () => {
            const answer = await backendClient.listThreads({
              projectId: project.id,
              size: 100,
              truncate: true,
            });
            expect(
              answer.threads.map((t) => t.id).sort(),
              'the API serves exactly the three seeded conversations',
            ).toEqual(seed.allThreadIds);
            // Asserted here as well as in the API test because this test's
            // subject is the agreement between the page and the API: if the API
            // answer were itself wrong, "the table matches the API" would be
            // satisfied by two wrong answers.
            for (const thread of seed.threads) {
              const row = answer.threads.find((t) => t.id === thread.threadId)!;
              expect(
                JSON.stringify(row.firstMessage),
                `${thread.key}: the API's First message is turn 1's input`,
              ).toContain(firstMarkerOf(thread));
              expect(
                JSON.stringify(row.lastMessage),
                `${thread.key}: the API's Last message is the last turn's output`,
              ).toContain(lastMarkerOf(thread));
            }
            return new Map(answer.threads.map((t) => [t.id, t]));
          },
        );

        await test.step('Open the project Logs on the Threads tab', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          // A longer readiness window than the 15s default: the fixture has
          // waited for every thread to be aggregated server-side, but the first
          // paint of a cold Logs page over a freshly seeded project on a shared
          // cloud workspace has been seen to exceed it.
          await logs.waitForThreadsReady(undefined, { timeout: 60_000 });
          expect(
            await logs.activeLogsTab(),
            'the Threads tab is the one on screen — `logsType` is persisted per project',
          ).toBe('threads');
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports all three seeded conversations',
              timeout: 60_000,
            })
            .toBe(seed.allThreadIds.length);
          expect(
            (await logs.readThreadIdsOnPage()).sort(),
            'and the table renders exactly them — the 24-hour window is not cutting the seed',
          ).toEqual(seed.allThreadIds);
        });

        await test.step('The table asked for the truncated branch', async () => {
          expect(
            threadRequests.length,
            'the page issued at least one threads-list read',
          ).toBeGreaterThan(0);
          expect(
            threadRequests.some(
              (url) => new URL(url).searchParams.get('truncate') === 'true',
            ),
            'the Threads table reads the list with truncate=true, which is the branch under test — ' +
              'if this deployment has `truncation_on_tables` off, this test is not asserting it',
          ).toBe(true);
        });

        await test.step('Every row shows its own turn 1 input and turn 3 output', async () => {
          for (const thread of seed.threads) {
            const first = (
              (await logs.threadFirstMessageCell(thread.threadId).textContent()) ?? ''
            ).trim();
            const last = (
              (await logs.threadLastMessageCell(thread.threadId).textContent()) ?? ''
            ).trim();

            expect(
              first,
              `${thread.key}: the First message cell shows turn 1's input`,
            ).toContain(firstMarkerOf(thread));
            for (const forbidden of forbiddenInFirst(thread)) {
              expect(
                first,
                `${thread.key}: the First message cell must not show ${forbidden}`,
              ).not.toContain(forbidden);
            }

            expect(
              last,
              `${thread.key}: the Last message cell shows the last turn's output`,
            ).toContain(lastMarkerOf(thread));
            for (const forbidden of forbiddenInLast(thread)) {
              expect(
                last,
                `${thread.key}: the Last message cell must not show ${forbidden}`,
              ).not.toContain(forbidden);
            }

            // And that the cell really received the truncated payload rather
            // than some short value that happened to carry the marker. Without
            // this, a build that served a 40-character stub for every
            // over-threshold message would pass every assertion above.
            // `PrettyCell` renders a non-prettifiable string verbatim up to its
            // own 10000-character display cap, so the truncated rows are orders
            // of magnitude longer than the control's prettified content.
            const expectedLong = {
              first: thread.firstMessageTruncated,
              last: thread.lastMessageTruncated,
            };
            for (const [side, text, long] of [
              ['First', first, expectedLong.first],
              ['Last', last, expectedLong.last],
            ] as const) {
              if (long) {
                expect(
                  text.length,
                  `${thread.key}: the ${side} message cell carries the truncated payload, ` +
                    'not a stub',
                ).toBeGreaterThan(5_000);
              } else {
                expect(
                  text.length,
                  `${thread.key}: the ${side} message cell is the short payload it was seeded with`,
                ).toBeLessThan(500);
              }
            }
          }
        });

        await test.step('The rendered rows agree with the API on which turn each came from', async () => {
          // Pinned to the endpoint as well as to the seed: the cell goes through
          // `PrettyCell`, so the rendered string is not the API's string and
          // cannot be compared byte for byte — but WHICH turn each side came
          // from is the same question on both surfaces, and that is comparable.
          for (const thread of seed.threads) {
            const row = apiRows.get(thread.threadId)!;
            const first = (
              (await logs.threadFirstMessageCell(thread.threadId).textContent()) ?? ''
            ).trim();
            const last = (
              (await logs.threadLastMessageCell(thread.threadId).textContent()) ?? ''
            ).trim();
            const markerIn = (haystack: string): string[] =>
              thread.turns
                .flatMap((t) => [t.inputMarker, t.outputMarker])
                .filter((m) => haystack.includes(m));

            expect(
              markerIn(first),
              `${thread.key}: the First cell and the API's first_message name the same turn`,
            ).toEqual(markerIn(JSON.stringify(row.firstMessage)));
            expect(
              markerIn(last),
              `${thread.key}: the Last cell and the API's last_message name the same turn`,
            ).toEqual(markerIn(JSON.stringify(row.lastMessage)));
          }
        });
      },
    );
  },
);

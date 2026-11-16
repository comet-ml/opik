import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * A Playground dataset run stores the prompt TEMPLATE in its experiment
 * config, not the template rendered against whichever item ran (opik#8547,
 * OPIK-7965).
 *
 * The run logs two different things on purpose: traces and spans keep
 * `providerMessages` — what the request actually sent, variables substituted —
 * while the experiment keeps `templateMessages`, the authored prompt with its
 * `{{variables}}` intact. Before this change the experiment got
 * `providerMessages` too, so the compare Configuration tab showed one dataset
 * item's values baked into the config, and two experiments over the same
 * prompt appeared to differ because their first item differed.
 *
 * That is textbook silent wrongness: a stored rendering is a perfectly normal
 * config row. It just describes one row of the dataset instead of the
 * experiment. `playground-experiment-name.spec.ts` already reads
 * `metadata.messages` off the POST, but only substring-matches a per-variant
 * marker — which is present in the template and in the rendering alike.
 *
 * Driven through the UI because the template never leaves the frontend as
 * typed: `usePromptDatasetItemCombination` is what picks `role` and `content`
 * out of the editor's own message state, and nothing about that is observable
 * from a hand-built request. The provider is a `custom-llm` one whose base URL
 * refuses every connection, the same way the sibling Playground specs stay
 * deterministic and key-free: the experiment is created when the run STARTS, so
 * what the provider does with the completion is irrelevant here.
 */
test.describe('Experiment compare — Playground prompt template in the config', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * The authored templates. Both carry a `{{variable}}`, and the system one
   * carries a SECOND column's variable: a rendering substitutes both, so a
   * template that kept only one of them would be half-broken in a way one
   * variable cannot show.
   */
  const SYSTEM_TEMPLATE = 'You are terse. The expected answer is {{expected_output}}.';
  const USER_TEMPLATE = 'Answer this: {{input}}';

  test(
    'the stored config is the template, and the Configuration tab shows it',
    { tag: ['@cap:experiments.configuration-tab'] },
    async ({
      project,
      dataset,
      providerKeys,
      backendClient,
      registerExperimentCleanup,
      testNamespace,
      page,
    }) => {
      test.setTimeout(240_000);

      const runName = `${testNamespace}-tmpl`;
      // `buildExperimentName` appends the variant's own letter, so the single
      // variant's experiment is `<runName>_a`.
      const experimentName = `${runName}_a`;
      const modelDisplayName = 'unreachable-model';

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('Author a two-message prompt with a variable in each', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          systemPrompt: SYSTEM_TEMPLATE,
          userPrompt: USER_TEMPLATE,
          modelDisplayName,
        });
      });

      await test.step('Load the dataset as the run source and name the run', async () => {
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({
          mode: 'dataset',
          entityName: dataset.name,
        });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: dataset.items.length });
        // Named rather than left auto-generated so the experiment this spec
        // then reads is identifiable, and so the run-prefix sweep in
        // `global-teardown.ts` can reach it if everything below fails.
        await playground.setExperimentName(runName);
      });

      const experimentId = await test.step('Run, and find the experiment it created', async () => {
        await playground.clickReRun();

        // Registered from inside the poll, like `playground-experiment-name.spec.ts`
        // does: the id does not exist until the run creates it, and the
        // assertions below are exactly the ones that fail when the run created
        // something unexpected.
        const seen = new Set<string>();
        const listNames = async (): Promise<string[]> => {
          const found = await backendClient.listExperimentsForDataset(dataset.id);
          for (const experiment of found) {
            if (seen.has(experiment.id)) continue;
            seen.add(experiment.id);
            registerExperimentCleanup(experiment.id, experiment.name);
          }
          return found.map((experiment) => experiment.name);
        };

        // The dataset's whole experiment list, not a lookup of the name we
        // expect: the fixture-seeded dataset has no other writer, so a second
        // experiment here would mean the run created something it should not
        // have — which a `find()` would never see.
        await expect
          .poll(listNames, { timeout: 180_000, intervals: [500, 1000, 2000, 5000] })
          .toEqual([experimentName]);

        const experiment = await backendClient.findExperimentByName(experimentName);
        expect(experiment, `the experiment named "${experimentName}"`).not.toBeNull();
        return experiment!.id;
      });

      await test.step('The stored config holds the template, and only role and content', async () => {
        const metadata = await backendClient.getExperimentMetadata(experimentId);

        // Compared whole, against the text that was typed. This is the
        // assertion the spec exists for, and it is three claims at once: the
        // `{{variables}}` survived (a rendering would carry "seed input 1" and
        // "seed output 1" instead), the roles are in the authored order, and
        // each message carries NOTHING but role and content — no `id`,
        // `promptId`, `promptVersionId` or `autoImprove` leaked out of the
        // editor's own state.
        //
        // A JSON array, not a string: a dataset run is created by the server's
        // experiment-execution endpoint, which stores `messages` structured, as
        // test-suite runs always have. `toEqual` against the array still fails
        // a stringified template rather than coercing it.
        expect(metadata.messages, 'the stored prompt template').toEqual([
          { role: 'system', content: SYSTEM_TEMPLATE },
          { role: 'user', content: USER_TEMPLATE },
        ]);
      });

      await test.step('The compare Configuration tab renders that same template', async () => {
        const compare = new CompareExperimentsPage(page, project.id, dataset.id, [experimentId]);
        await compare.gotoConfiguration();
        await compare.expectExperimentColumnsInConfiguration([
          { id: experimentId, name: experimentName },
        ]);

        // One row per message field, because the tab flattens structured
        // metadata by path. Each content cell is matched exactly, which also
        // rules out a rendering: that would carry the first dataset item's
        // values in place of both `{{variables}}`.
        const template = [
          { role: 'system', content: SYSTEM_TEMPLATE },
          { role: 'user', content: USER_TEMPLATE },
        ];
        for (const [index, message] of template.entries()) {
          expect(
            await compare.configCellText(`messages.${index}.role`, experimentId),
            `the role row of message ${index}`,
          ).toBe(message.role);
          expect(
            await compare.configCellText(`messages.${index}.content`, experimentId),
            `the content row of message ${index}`,
          ).toBe(message.content);
        }
      });
    },
  );
});

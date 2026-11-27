import { test as baseTest } from './experiments-filter-chip.fixture';

/**
 * The payload `jsonable_encoder.encode` must produce for the numpy one, keyed
 * exactly as the bridge builds it.
 *
 * Held here, in the suite, as well as being returned by the bridge: the spec
 * compares the two, so a change to `routes/numpy_payloads.py` fails loudly
 * rather than quietly redefining what the spec expects. Without that, the
 * "expected" value would be whatever the code under test's own neighbour
 * happened to produce.
 *
 * `dt64` and `td64` are strings on purpose — those are the two numpy types
 * opik#9573 deliberately leaves as text, so text IS the plain-Python
 * equivalent. `arr` is a JSON list, which is the pre-existing `ndarray` branch.
 */
export const NUMPY_EXPECTED_PAYLOAD = {
  i64: 7,
  f32: 1.5,
  b: true,
  dt64: '2026-11-23',
  td64: '5 seconds',
  arr: [2, 3],
} as const;

/**
 * The numeric metadata key the filter steps address, and the value it carries.
 *
 * Named rather than inlined because the filters are written either side of it
 * (`> 6` matches, `> 7` does not), and a spec that drifted from the payload
 * would assert a window around the wrong number.
 */
export const NUMPY_NUMERIC_KEY = 'i64';
export const NUMPY_NUMERIC_VALUE = 7;

export interface NumpyPayloadTracesRef {
  projectId: string;
  projectName: string;
  /** The trace whose payload was built from numpy scalars. */
  numpyTraceId: string;
  numpyTraceName: string;
  /** The same payload in plain Python — the control. */
  controlTraceId: string;
  controlTraceName: string;
  /**
   * The control payload as the BRIDGE built it. Compared by the spec against
   * `NUMPY_EXPECTED_PAYLOAD` rather than trusted, so the two cannot drift.
   */
  expected: Record<string, unknown>;
  /** Diagnostic only; nothing asserts a numpy version. */
  numpyVersion: string;
}

export interface NumpyPayloadTracesFixtures {
  numpyPayloadTraces: NumpyPayloadTracesRef;
}

/**
 * One project holding exactly two traces: a numpy-scalar payload and its
 * plain-Python control.
 *
 * Deliberately thin. The things worth asserting about this seed — that both
 * traces arrived with their fields intact, and that the two stored payloads
 * agree — are what the spec is ABOUT, and a fixture that pre-asserted them
 * would be deciding the test's verdict in setup. What the fixture does own is
 * the lifecycle: `deleteProject` does not cascade to traces, and
 * `global-teardown`'s run-prefix sweep does not know about them, so the project
 * is registered with `registerProjectTracesCleanup` or the two rows outlive the
 * run.
 *
 * Exactly two traces matters: it is what lets the spec assert a filtered read
 * by exhaustion (`total`, and the ids) instead of finding its own row in
 * whatever else the project happened to hold.
 */
export const test = baseTest.extend<NumpyPayloadTracesFixtures>({
  numpyPayloadTraces: async (
    { sdkClient, project, testNamespace, registerProjectTracesCleanup },
    use,
    testInfo,
  ) => {
    registerProjectTracesCleanup(project.id);

    const numpyTraceName = `${testNamespace}-numpy`;
    const controlTraceName = `${testNamespace}-control`;
    const seeded = await sdkClient.python.numpyPayloadTraces({
      project_name: project.name,
      numpy_trace_name: numpyTraceName,
      control_trace_name: controlTraceName,
    });

    const ref: NumpyPayloadTracesRef = {
      projectId: project.id,
      projectName: project.name,
      numpyTraceId: seeded.numpy_trace_id,
      numpyTraceName,
      controlTraceId: seeded.control_trace_id,
      controlTraceName,
      expected: seeded.expected,
      numpyVersion: seeded.numpy_version,
    };
    await testInfo.attach('opik.numpyPayloadTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './experiments-filter-chip.fixture';

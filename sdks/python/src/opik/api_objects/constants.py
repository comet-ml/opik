FEEDBACK_SCORE_SOURCE_SDK = "sdk"
DATASET_SOURCE_SDK = "sdk"

FEEDBACK_SCORES_MAX_BATCH_SIZE = 1000
EXPERIMENT_ITEMS_MAX_BATCH_SIZE = 1000
DATASET_ITEMS_MAX_BATCH_SIZE = 1000
ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000
DELETE_TRACE_BATCH_SIZE = 1000

DATASET_STREAM_BATCH_SIZE = 2000

# Parallel dataset insert relies on the backend serializing concurrent dataset
# version writes; before this release, concurrent batches sharing one
# batch_group_id raced and could 500 or silently drop rows (OPIK-7264, backend
# PR #7518). Not user-tunable: lowering it re-opens that race.
MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT = "2.2.8"

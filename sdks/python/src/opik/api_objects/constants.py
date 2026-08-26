FEEDBACK_SCORE_SOURCE_SDK = "sdk"
DATASET_SOURCE_SDK = "sdk"

FEEDBACK_SCORES_MAX_BATCH_SIZE = 1000
EXPERIMENT_ITEMS_MAX_BATCH_SIZE = 1000
EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE = 1000

# The bulk endpoint rejects any request whose *serialized* body exceeds 4MB
# (MaxRequestSize.java). The backend measures the whole request, envelope
# fields included, so we batch against a lower ceiling to leave headroom for
# experiment_name/dataset_name/experiment_id/project_name and for the gap
# between our size estimate and real JSON encoding.
EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB = 3.5

# Ceiling on upload threads, matching the file-upload pool. Guards against a
# caller passing an arbitrarily large num_threads.
EXPERIMENT_ITEMS_BULK_MAX_THREADS = 32
DATASET_ITEMS_MAX_BATCH_SIZE = 1000
ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000
DELETE_TRACE_BATCH_SIZE = 1000

DATASET_STREAM_BATCH_SIZE = 2000

# Parallel dataset insert requires a backend that serializes concurrent dataset
# version writes. On backends older than this version, concurrent batches
# sharing one batch_group_id raced and could 500 or silently drop rows; 2.2.8 is
# the first release containing the fix (OPIK-7264,
# https://github.com/comet-ml/opik/pull/7518). Not user-tunable: lowering it
# re-opens that race.
MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT = "2.2.8"

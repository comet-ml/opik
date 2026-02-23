# Data migration: Fix experiment items with wrong dataset_item_id (OPIK-4518)

After dataset versioning was introduced, the API incorrectly returned the version-specific row `id` from
`dataset_item_versions` instead of the stable `dataset_item_id`. SDKs that created experiment items during
this period stored the wrong UUID in `experiment_items.dataset_item_id`.

**If your installation did not create experiments against versioned datasets (v2+) before this fix was deployed,
there is no need to run this migration.**

Since `dataset_item_id` is part of the ClickHouse `ORDER BY` key for `experiment_items`, the field cannot be
updated in place. The migration inserts corrected rows (which win ReplacingMergeTree deduplication) and then
deletes the old incorrect rows.

## Instructions

Connect to your ClickHouse instance and run the following script:
- [000001_fix_experiment_items_dataset_item_ids](scripts/db-app-analytics/000001_fix_experiment_items_dataset_item_ids.sql)

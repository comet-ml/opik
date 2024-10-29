# Data migration for dataset items `data` field

To adapt the old dataset item rows to the new structure of dynamic fields, this data migration will make sure
all dataset item rows are backfilled properly. **Important: If your installation didn't generate datasets prior to this release, there is no need to run such migration**.

We recommend running such a migration outside peak hours as it may consume significant resources from your data node. Please follow the steps described in the migration file to guarantee the migration's safe execution.

**ClickHouse** doesn't offer loops or more complex script capabilities. For this reason, the migration SQL contains 
step-by-step instructions on how to proceed with the data migration. Please connect to your ClickHouse instance and run the following scripts:
- [000001_data_migration_dataset_items_data_field](scripts/db-app-analytics/000001_data_migration_dataset_items_data_field.sql)

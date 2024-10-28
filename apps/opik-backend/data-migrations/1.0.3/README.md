# Data migration for dataset items `data` field

In order to adapt the old dataset items rows to the new structure of dynamic fields, this data migration will make sure
all dataset items rows are back-filled.

We recommend running such migration out of peak hours at may consumer many resources from the data nodes. Please follow
the steps described in the migration file so guarantee the safe execution of the migration.

**ClickHouse** doesn't offer loops are more complex script capabilities, for this reason the migration sql contain 
step-by-step instructions. Please, connect to your ClickHouse instance and run the following scripts:
- [000001_data_migration_dataset_items_data_field](scripts/db-app-analytics/000001_data_migration_dataset_items_data_field.sql)
# 2024 Changelog

### Release 1.7.0, 2025-04-09 [#](https://www.comet.com/docs/opik/self-host/local_deployment#troubleshooting)

#### Backward Incompatible Change

In this release, we migrated the Clickhouse table engines to their replicated version. The migration was automated, and we don't expect any errors. However, if you have any issues, please check [this link](https://www.comet.com/docs/opik/self-host/local_deployment#troubleshooting) or feel free to open an issue in this repository.

### Release 1.0.3, 2024-10-29 [#](apps/opik-backend/data-migrations/1.0.3/README.md)

#### Backward Incompatible Change

The structure of dataset items has changed to include new dynamic fields. Dataset items logged before version 1.0.3 will still show but would not be searchable. 
If you would like to migrate previous dataset items to the new format, please see the instructions below: [dataset item migration](apps/opik-backend/data-migrations/1.0.3/README.md) for more information*.



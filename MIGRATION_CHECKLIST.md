# Dataset Versioning Migration - Pre-Production Checklist

## Phase 1: Local Development Testing ✅

### Migration Script Validation
- [x] MySQL migration follows Liquibase format
- [x] ClickHouse migration follows Liquibase format
- [x] Both migrations include proper changeset metadata
- [x] Both migrations include rollback statements
- [x] Both migrations are idempotent (safe to re-run)
- [x] Migration scripts have no linter errors

### Documentation Completeness
- [x] Comprehensive migration guide created
- [x] Quick start guide created
- [x] Test/validation scripts created
- [x] Summary document created
- [x] All validation queries documented

### Design Review
- [x] Deterministic ID strategy (version 1 = dataset ID)
- [x] Cross-database coordination without application code
- [x] Data preservation (timestamps, creators)
- [x] Migration markers for tracking

## Phase 2: Staging Environment Testing ⏳

### Pre-Migration Checks
- [ ] Backup staging database (MySQL + ClickHouse)
- [ ] Document current dataset count
- [ ] Document current item count per dataset
- [ ] Check disk space on ClickHouse (need 2x dataset_items size)
- [ ] Verify Liquibase is up to date

### Migration Execution
- [ ] Run migration: `mvn liquibase:update`
- [ ] Monitor migration progress
- [ ] Check for errors in logs
- [ ] Record migration duration

### Post-Migration Validation
- [ ] Run all validation queries from `test_dataset_versioning_migration.sql`
- [ ] Verify dataset count matches
- [ ] Verify item counts match per dataset
- [ ] Verify version 1 created for all datasets
- [ ] Verify 'latest' tags created for all datasets
- [ ] Verify version ID = dataset ID for all versions
- [ ] Verify data integrity (sample check)
- [ ] Check ClickHouse disk usage

### Rollback Testing
- [ ] Run rollback: `mvn liquibase:rollback -Dliquibase.rollbackCount=2`
- [ ] Verify all migrated data removed
- [ ] Run validation queries to confirm clean rollback
- [ ] Re-run migration to verify idempotency
- [ ] Verify migration succeeds on second run

### Application Testing
- [ ] Test reading datasets (should work as before)
- [ ] Test reading dataset items (should work as before)
- [ ] Verify no application errors in logs
- [ ] Test API endpoints still work
- [ ] Verify SDK compatibility

## Phase 3: Production Preparation ⏳

### Infrastructure Readiness
- [ ] Verify production ClickHouse has sufficient disk space
- [ ] Schedule maintenance window (low-traffic period)
- [ ] Notify stakeholders of maintenance window
- [ ] Prepare rollback plan
- [ ] Set up monitoring alerts

### Pre-Production Validation
- [ ] Review staging test results with team
- [ ] Get approval from tech lead
- [ ] Get approval from product owner
- [ ] Document any issues found in staging
- [ ] Update migration scripts if needed

### Backup Strategy
- [ ] Create full MySQL backup
- [ ] Create full ClickHouse backup
- [ ] Verify backups are restorable
- [ ] Document backup locations
- [ ] Prepare restore procedures

### Communication Plan
- [ ] Draft maintenance announcement
- [ ] Prepare status update template
- [ ] Identify escalation contacts
- [ ] Schedule team availability during migration

## Phase 4: Production Migration ⏳

### Pre-Migration
- [ ] Send maintenance announcement
- [ ] Verify all team members available
- [ ] Create production database backups
- [ ] Run pre-migration validation queries
- [ ] Document baseline metrics (dataset count, item counts)

### Migration Execution
- [ ] Start maintenance window
- [ ] Run migration: `mvn liquibase:update`
- [ ] Monitor migration progress
- [ ] Check for errors in logs
- [ ] Record migration duration
- [ ] Monitor system resources (CPU, memory, disk)

### Post-Migration Validation
- [ ] Run all validation queries
- [ ] Verify dataset count matches baseline
- [ ] Verify item counts match baseline per dataset
- [ ] Verify version 1 created for all datasets
- [ ] Verify 'latest' tags created for all datasets
- [ ] Verify version ID = dataset ID
- [ ] Verify data integrity (sample check)
- [ ] Check ClickHouse disk usage

### Application Verification
- [ ] Restart application services
- [ ] Test reading datasets
- [ ] Test reading dataset items
- [ ] Verify no errors in application logs
- [ ] Test API endpoints
- [ ] Verify SDK compatibility
- [ ] Run smoke tests

### Go-Live Decision
- [ ] All validation queries pass ✅
- [ ] No errors in application logs ✅
- [ ] API endpoints working ✅
- [ ] Team approval to proceed ✅
- [ ] Send completion announcement

## Phase 5: Post-Migration Monitoring ⏳

### Immediate Monitoring (First 24 Hours)
- [ ] Monitor application error rates
- [ ] Monitor database performance
- [ ] Monitor ClickHouse disk usage
- [ ] Check for any user-reported issues
- [ ] Review application logs for anomalies

### Short-term Monitoring (First Week)
- [ ] Daily review of error logs
- [ ] Monitor query performance
- [ ] Track disk space growth
- [ ] Collect user feedback
- [ ] Document any issues

### Feature Toggle Preparation
- [ ] Verify migration stable for 1 week
- [ ] Review all monitoring data
- [ ] Get team approval to enable feature toggle
- [ ] Plan feature toggle rollout strategy
- [ ] Update documentation for new versioning behavior

## Rollback Procedure (If Needed)

### Immediate Rollback (During Migration)
1. [ ] Stop migration if errors occur
2. [ ] Run: `mvn liquibase:rollback -Dliquibase.rollbackCount=2`
3. [ ] Verify rollback with validation queries
4. [ ] Restart application services
5. [ ] Verify application working normally
6. [ ] Investigate root cause
7. [ ] Update migration scripts
8. [ ] Re-test in staging

### Post-Migration Rollback (After Go-Live)
1. [ ] Assess impact and urgency
2. [ ] Get approval from tech lead
3. [ ] Announce rollback to stakeholders
4. [ ] Run: `mvn liquibase:rollback -Dliquibase.rollbackCount=2`
5. [ ] Verify rollback with validation queries
6. [ ] Restart application services
7. [ ] Verify application working normally
8. [ ] Investigate root cause
9. [ ] Document lessons learned

## Success Criteria

### Migration Success
- ✅ All validation queries pass
- ✅ Dataset count matches baseline
- ✅ Item counts match baseline per dataset
- ✅ No data loss
- ✅ No application errors
- ✅ Migration completed within expected time

### Application Success
- ✅ All API endpoints working
- ✅ SDK compatibility maintained
- ✅ No increase in error rates
- ✅ No performance degradation
- ✅ User experience unchanged

### Infrastructure Success
- ✅ Disk space within acceptable limits
- ✅ Database performance stable
- ✅ No resource exhaustion
- ✅ Monitoring alerts functioning

## Risk Mitigation

### High Risk: Data Loss
- **Mitigation:** Full backups before migration
- **Detection:** Validation queries comparing counts
- **Response:** Immediate rollback and restore from backup

### Medium Risk: Migration Timeout
- **Mitigation:** Test on staging with production-like data volume
- **Detection:** Monitor migration duration
- **Response:** Increase timeout or schedule longer maintenance window

### Medium Risk: Disk Space Exhaustion
- **Mitigation:** Verify 2x dataset_items size available
- **Detection:** Monitor disk usage during migration
- **Response:** Pause migration, free up space, resume

### Low Risk: Application Incompatibility
- **Mitigation:** Test application after staging migration
- **Detection:** Application error logs and smoke tests
- **Response:** Rollback migration, fix application, retry

## Contact Information

### Escalation Path
1. **Migration Lead:** [Name] - [Contact]
2. **Tech Lead:** [Name] - [Contact]
3. **Database Admin:** [Name] - [Contact]
4. **On-Call Engineer:** [Name] - [Contact]

### Communication Channels
- **Slack Channel:** #dataset-versioning-migration
- **Incident Channel:** #incidents
- **Status Page:** [URL]

## References

- [Migration Plan](/.cursor/plans/dataset_versioning_refactor_c1f6fcd7.plan.md)
- [Comprehensive Migration Guide](apps/opik-backend/src/main/resources/liquibase/DATASET_VERSIONING_MIGRATION.md)
- [Quick Start Guide](apps/opik-backend/src/main/resources/liquibase/MIGRATION_QUICK_START.md)
- [Test Scripts](apps/opik-backend/src/main/resources/liquibase/test_dataset_versioning_migration.sql)
- [Implementation Summary](DATASET_VERSIONING_MIGRATION_SUMMARY.md)

---

**Last Updated:** 2025-12-31  
**Version:** 1.0  
**Status:** Ready for Staging Testing


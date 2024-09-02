# Opik

How to start the Opik application
---

1. Run `mvn clean install` to build your application
1. Start application with `java -jar target/opik-backend-{project.pom.version}.jar server config.yml`
1. To check that your application is running enter url `http://localhost:8080`

Health Check
---

To see your applications health enter url `http://localhost:8081/healthcheck`

Run migrations
---

1. Check pending
   migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} status config.yml`
2. Run migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} migrate config.yml`
3. Create schema
   tag `java -jar target/opik-backend-{project.pom.version}.jar {database} tag config.yml {tag_name}`
3. Rollback
   migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} rollback config.yml --count 1`
   OR `java -jar target/opik-backend-{project.pom.version}.jar {database} rollback config.yml --tag {tag_name}`

Replace `{project.pom.version}` with the version of the project in the pom file.
Replace `{database}` with `db` for MySQL migrations and with `dbAnalytics` for ClickHouse migrations.


```
SHOW DATABASES

Query id: a9faa739-5565-4fc5-8843-5dc0f72ff46d

┌─name───────────────┐
│ INFORMATION_SCHEMA │
│ opik     │
│ default            │
│ information_schema │
│ system             │
└────────────────────┘

5 rows in set. Elapsed: 0.004 sec. 
```

* You can curl the ClickHouse REST endpoint
  with `echo 'SELECT version()' | curl -H 'X-ClickHouse-User: opik' -H 'X-ClickHouse-Key: opik' 'http://localhost:8123/' -d @-`.
  Sample result: `23.8.15.35`.
* You can stop the application with `docker-compose -f apps/opik-backend/docker-compose.yml down`.

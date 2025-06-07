select *
from opik_prod.traces
where id = 'b7ec57e9-998f-47db-8f73-9f8d64bec266'


with span_created_from as (
select
    trace_id,
    first_value(JSONExtractString(metadata, 'created_from')) IGNORE NULLS as span_created_from
from opik_prod.spans
where start_time >= '2025-05-01'
  and start_time < '2025-05-21'
group by 1
),
trace_data as (
select
    id,
    coalesce(span_created_from, JSONExtractString(metadata, 'created_from')) as created_from,
    input,
    output,
    metadata,
    created_by
from opik_prod.traces
left join span_created_from
    on opik_prod.traces.id = span_created_from.trace_id
where start_time >= '2025-05-01'
  and start_time < '2025-05-21'
)
select
    *
from trace_data
limit 1000
-- group by 1
-- order by 2 desc

select * from
opik_prod.traces
where id = '019739e4-7587-7623-8f49-5b343ae1fb3c'

select * from
opik_prod.spans
where trace_id = '0196aeb6-4b33-7461-bfa2-3c8292d06049'

select trace_id,
       first_value(span_created_from)
       from (
        
select
    trace_id,
    JSONExtractString(metadata, 'created_from') as span_created_from
from opik_prod.spans
where start_time >= '2025-05-01'
  and start_time < '2025-05-21'
  and trace_id = '0196aeb6-4b33-7461-bfa2-3c8292d06049'
having span_created_from != ''
       )
       group by 1

-- 允许同一会话存在多条活动 run（RUNNING + 若干 QUEUED），串行改由 SessionRunGate 保证。
DROP INDEX IF EXISTS ra_one_active_run;

-- 会话内严格 FIFO 的排序依据；同一毫秒创建的两个 run 不能靠 id 决定先后。
CREATE SEQUENCE IF NOT EXISTS ra_run_queue_seq;

ALTER TABLE ra_run ADD COLUMN IF NOT EXISTS queue_seq BIGINT;

-- 历史数据按确定性顺序回填，再将序列推进到最大值。
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS seq
    FROM ra_run
    WHERE queue_seq IS NULL
)
UPDATE ra_run r
SET queue_seq = ordered.seq
FROM ordered
WHERE r.id = ordered.id;

SELECT setval('ra_run_queue_seq', COALESCE(MAX(queue_seq), 1), COUNT(*) > 0) FROM ra_run;

ALTER TABLE ra_run ALTER COLUMN queue_seq SET NOT NULL;
ALTER TABLE ra_run ALTER COLUMN queue_seq SET DEFAULT nextval('ra_run_queue_seq');

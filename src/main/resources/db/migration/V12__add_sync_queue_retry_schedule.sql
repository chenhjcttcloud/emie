ALTER TABLE sync_queue ADD COLUMN next_retry_at DATETIME NULL;
CREATE INDEX idx_sync_status_retry ON sync_queue (status, next_retry_at, created_at);

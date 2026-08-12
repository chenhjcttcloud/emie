CREATE TABLE IF NOT EXISTS point_adjustment_ledgers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(100) NOT NULL,
 source_type VARCHAR(40) NOT NULL, source_id BIGINT NOT NULL, points INT NOT NULL,
 reason VARCHAR(500) NOT NULL, created_by VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_point_adjustment_source UNIQUE (source_type, source_id),
 CONSTRAINT chk_point_adjustment_nonzero CHECK (points <> 0),
 INDEX idx_point_adjustment_user_created (user_id, created_at)
);

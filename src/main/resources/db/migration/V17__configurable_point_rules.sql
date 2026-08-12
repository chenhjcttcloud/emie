ALTER TABLE point_rules ADD COLUMN category VARCHAR(50) NULL;
ALTER TABLE point_rules ADD COLUMN difficulty_multiplier DOUBLE NOT NULL DEFAULT 1.0;
ALTER TABLE point_rules ADD COLUMN quality_bonus_threshold INT NOT NULL DEFAULT 0;
ALTER TABLE point_rules ADD COLUMN quality_bonus_ratio DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE point_rules ADD COLUMN count_in_performance BOOLEAN NOT NULL DEFAULT TRUE;

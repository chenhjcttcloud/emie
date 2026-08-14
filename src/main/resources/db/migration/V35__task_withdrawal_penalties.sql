CREATE TABLE IF NOT EXISTS task_withdrawals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sub_task_id BIGINT NOT NULL,
  user_id VARCHAR(100) NOT NULL,
  elapsed_minutes BIGINT NOT NULL,
  penalty_ratio DOUBLE NOT NULL,
  penalty_points INT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at DATETIME NOT NULL,
  CONSTRAINT fk_task_withdrawal_task FOREIGN KEY (sub_task_id) REFERENCES sub_tasks(id),
  INDEX idx_task_withdrawal_user (user_id),
  INDEX idx_task_withdrawal_task (sub_task_id)
);

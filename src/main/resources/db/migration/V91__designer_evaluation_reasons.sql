ALTER TABLE monthly_designer_evaluations
    ADD COLUMN completion_reason VARCHAR(2000) NULL,
    ADD COLUMN collaboration_reason VARCHAR(2000) NULL,
    ADD COLUMN attendance_reason VARCHAR(2000) NULL,
    ADD COLUMN weekly_report_reason VARCHAR(2000) NULL,
    ADD COLUMN weekly_report_deduction_reason VARCHAR(2000) NULL;

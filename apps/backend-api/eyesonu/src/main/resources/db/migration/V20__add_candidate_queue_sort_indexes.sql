-- Keep queue ordering indexable without evaluating CASE expressions during reads.
ALTER TABLE `candidates`
    ADD COLUMN `review_priority` TINYINT
        GENERATED ALWAYS AS (
            CASE `review_status`
                WHEN 'PENDING' THEN 0
                WHEN 'KEPT' THEN 1
                WHEN 'CONFIRMED' THEN 2
                WHEN 'REJECTED' THEN 3
                ELSE 4
            END
        ) VIRTUAL AFTER `review_status`,
    ADD COLUMN `source_priority` TINYINT
        GENERATED ALWAYS AS (
            CASE `source_type`
                WHEN 'REALTIME' THEN 0
                WHEN 'RECORDING_ANALYSIS' THEN 1
                ELSE 2
            END
        ) VIRTUAL AFTER `source_type`,
    ADD KEY `ix_candidates_review_source_queue`
        (`review_priority`, `source_priority`, `last_detected_at` DESC, `id` DESC),
    ADD KEY `ix_candidates_source_review_queue`
        (`source_priority`, `review_priority`, `last_detected_at` DESC, `id` DESC),
    ALGORITHM=INPLACE,
    LOCK=NONE;

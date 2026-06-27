WITH duplicate_source_courses AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY owner_user_id, source_ride_record_id
               ORDER BY id
           ) AS duplicate_rank
    FROM courses
    WHERE owner_user_id IS NOT NULL
      AND source_ride_record_id IS NOT NULL
)
UPDATE courses
SET source_ride_record_id = NULL
WHERE id IN (
    SELECT id
    FROM duplicate_source_courses
    WHERE duplicate_rank > 1
);

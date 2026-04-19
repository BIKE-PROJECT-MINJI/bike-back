WITH next_orders AS (
    SELECT
        COALESCE(MAX(display_order), 0) + 1 AS next_display_order,
        COALESCE(MAX(featured_rank), 0) + 1 AS next_featured_rank
    FROM courses
), inserted_course AS (
    INSERT INTO courses (
        title,
        description,
        distance_km,
        estimated_duration_min,
        display_order,
        curated,
        featured_rank,
        start_latitude,
        start_longitude,
        visibility
    )
    SELECT
        '서울대입구 테스트 루프',
        '서울대입구역 3번 출구 인근에서 출발해 샤로수길 입구와 관악구청 방향을 가볍게 확인하는 실사용 테스트용 코스입니다.',
        3.2,
        18,
        next_display_order,
        TRUE,
        next_featured_rank,
        37.4813850,
        126.9527790,
        'PUBLIC'
    FROM next_orders
    WHERE NOT EXISTS (
        SELECT 1
        FROM courses
        WHERE title = '서울대입구 테스트 루프'
    )
    RETURNING id
), target_course AS (
    SELECT id FROM inserted_course
    UNION ALL
    SELECT id
    FROM courses
    WHERE title = '서울대입구 테스트 루프'
    LIMIT 1
)
INSERT INTO course_route_points (course_id, point_order, latitude, longitude)
SELECT
    target_course.id,
    route_points.point_order,
    route_points.latitude,
    route_points.longitude
FROM target_course
CROSS JOIN (
    VALUES
        (1, 37.4813850::NUMERIC(10,7), 126.9527790::NUMERIC(10,7)),
        (2, 37.4819100::NUMERIC(10,7), 126.9533200::NUMERIC(10,7)),
        (3, 37.4824200::NUMERIC(10,7), 126.9540300::NUMERIC(10,7)),
        (4, 37.4828600::NUMERIC(10,7), 126.9549200::NUMERIC(10,7)),
        (5, 37.4831400::NUMERIC(10,7), 126.9557400::NUMERIC(10,7)),
        (6, 37.4825500::NUMERIC(10,7), 126.9561200::NUMERIC(10,7)),
        (7, 37.4818700::NUMERIC(10,7), 126.9559100::NUMERIC(10,7)),
        (8, 37.4812000::NUMERIC(10,7), 126.9553400::NUMERIC(10,7)),
        (9, 37.4808300::NUMERIC(10,7), 126.9545000::NUMERIC(10,7)),
        (10, 37.4809100::NUMERIC(10,7), 126.9534800::NUMERIC(10,7)),
        (11, 37.4811200::NUMERIC(10,7), 126.9529400::NUMERIC(10,7)),
        (12, 37.4813850::NUMERIC(10,7), 126.9527790::NUMERIC(10,7))
) AS route_points(point_order, latitude, longitude)
WHERE NOT EXISTS (
    SELECT 1
    FROM course_route_points existing
    WHERE existing.course_id = target_course.id
);

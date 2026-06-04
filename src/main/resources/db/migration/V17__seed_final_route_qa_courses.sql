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
        '안양천 탄천 연결 검수 코스',
        '안양천 합수부에서 한강 남단과 탄천 합수부를 지나 건대입구 방향까지 이어지는 최종 route QA용 공개 코스입니다.',
        18.4,
        52,
        next_display_order,
        TRUE,
        next_featured_rank,
        37.5483000,
        126.8855000,
        'PUBLIC'
    FROM next_orders
    WHERE NOT EXISTS (
        SELECT 1
        FROM courses
        WHERE title = '안양천 탄천 연결 검수 코스'
    )
    RETURNING id
), target_course AS (
    SELECT id FROM inserted_course
    UNION ALL
    SELECT id
    FROM courses
    WHERE title = '안양천 탄천 연결 검수 코스'
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
        (1, 37.5483000::NUMERIC(10,7), 126.8855000::NUMERIC(10,7)),
        (2, 37.5359000::NUMERIC(10,7), 126.9027000::NUMERIC(10,7)),
        (3, 37.5201000::NUMERIC(10,7), 126.9284000::NUMERIC(10,7)),
        (4, 37.5123000::NUMERIC(10,7), 126.9639000::NUMERIC(10,7)),
        (5, 37.5176000::NUMERIC(10,7), 127.0158000::NUMERIC(10,7)),
        (6, 37.5279000::NUMERIC(10,7), 127.0666000::NUMERIC(10,7)),
        (7, 37.5345000::NUMERIC(10,7), 127.0704000::NUMERIC(10,7)),
        (8, 37.5403720::NUMERIC(10,7), 127.0692760::NUMERIC(10,7))
) AS route_points(point_order, latitude, longitude)
WHERE NOT EXISTS (
    SELECT 1
    FROM course_route_points existing
    WHERE existing.course_id = target_course.id
);

UPDATE courses c
SET route_line_geom = route_line.route_line_geom
FROM (
    SELECT
        course_id,
        ST_MakeLine(point_geom ORDER BY point_order)::geometry(LineString, 4326) AS route_line_geom
    FROM course_route_points
    GROUP BY course_id
    HAVING COUNT(*) >= 2
) route_line
WHERE c.id = route_line.course_id
  AND c.title IN ('서울대입구 테스트 루프', '안양천 탄천 연결 검수 코스');

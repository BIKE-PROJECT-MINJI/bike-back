package com.bikeprojectminji.bikeback.course.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CourseRepositoryImplTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @Mock
    private TypedQuery<CourseEntity> courseBatchQuery;

    @Mock
    private TypedQuery<CourseListRow> courseListRowQuery;

    @Mock
    private TypedQuery<CoursePageCursorAnchor> coursePageCursorAnchorQuery;

    @Test
    @DisplayName("공개 코스 목록은 courses 본테이블이 아니라 목록 전용 read model에서 조회한다")
    void findPublicPageAfterUsesListReadModelWithoutEntityHydration() {
        CourseRepositoryImpl repository = new CourseRepositoryImpl(entityManager);
        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("course_list_summaries")))
                .willReturn(nativeQuery);
        given(nativeQuery.setParameter("limit", 2)).willReturn(nativeQuery);
        given(nativeQuery.getResultList()).willReturn(Collections.singletonList(
                new Object[]{1L, "한강 코스", BigDecimal.valueOf(10.5), 45}
        ));

        List<CourseListRow> rows = repository.findPublicListPageAfter(null, 2);

        assertThat(rows).extracting(CourseListRow::id).containsExactly(1L);
        assertThat(rows).extracting(CourseListRow::title).containsExactly("한강 코스");
        verify(entityManager, never()).find(eq(CourseEntity.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("cursor가 있으면 목록 read model에서 displayOrder/id anchor만 조회한다")
    void findPublicPageAfterUsesCursorAnchorFromReadModel() {
        CourseRepositoryImpl repository = new CourseRepositoryImpl(entityManager);
        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("where course_id = :id")))
                .willReturn(nativeQuery);
        given(nativeQuery.setParameter("id", 10L)).willReturn(nativeQuery);
        given(nativeQuery.getResultList()).willReturn(Collections.singletonList(new Object[]{10L, 10}));
        Query pageQuery = org.mockito.Mockito.mock(Query.class);
        given(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("display_order > :displayOrder")))
                .willReturn(pageQuery);
        given(pageQuery.setParameter("displayOrder", 10)).willReturn(pageQuery);
        given(pageQuery.setParameter("id", 10L)).willReturn(pageQuery);
        given(pageQuery.setParameter("limit", 2)).willReturn(pageQuery);
        given(pageQuery.getResultList()).willReturn(Collections.singletonList(
                new Object[]{11L, "다음 코스", BigDecimal.valueOf(12.5), 50}
        ));

        List<CourseListRow> rows = repository.findPublicListPageAfter(10L, 2);

        assertThat(rows).extracting(CourseListRow::id).containsExactly(11L);
        verify(entityManager, never()).find(eq(CourseEntity.class), eq(10L));
    }

    @Test
    @DisplayName("위치 기반 추천 코스 조회는 native 결과 id를 batch fetch하고 원래 거리순 순서를 유지한다")
    void findFeaturedCoursesNearBatchFetchesEntitiesInNativeResultOrder() {
        CourseRepositoryImpl repository = new CourseRepositoryImpl(entityManager);
        CourseEntity far = course(1L, "먼 코스");
        CourseEntity near = course(2L, "가까운 코스");
        given(entityManager.createNativeQuery(anyString())).willReturn(nativeQuery);
        given(nativeQuery.setParameter(eq("lat"), eq(BigDecimal.valueOf(37.5)))).willReturn(nativeQuery);
        given(nativeQuery.setParameter(eq("lon"), eq(BigDecimal.valueOf(127.0)))).willReturn(nativeQuery);
        given(nativeQuery.setParameter(eq("limit"), eq(2))).willReturn(nativeQuery);
        given(nativeQuery.getResultList()).willReturn(List.of(
                new Object[]{2L, 30},
                new Object[]{1L, 120}
        ));
        given(entityManager.createQuery("select c from CourseEntity c where c.id in :ids", CourseEntity.class))
                .willReturn(courseBatchQuery);
        given(courseBatchQuery.setParameter("ids", List.of(2L, 1L))).willReturn(courseBatchQuery);
        given(courseBatchQuery.getResultList()).willReturn(List.of(far, near));

        List<FeaturedCourseDistanceCandidate> candidates = repository.findFeaturedCoursesNear(
                BigDecimal.valueOf(37.5),
                BigDecimal.valueOf(127.0),
                2
        );

        assertThat(candidates).extracting(candidate -> candidate.course().getId())
                .containsExactly(2L, 1L);
        assertThat(candidates).extracting(FeaturedCourseDistanceCandidate::distanceFromUserM)
                .containsExactly(30, 120);
        verify(entityManager, never()).find(eq(CourseEntity.class), eq(2L));
        verify(entityManager, never()).find(eq(CourseEntity.class), eq(1L));
    }

    private CourseEntity course(Long id, String title) {
        CourseEntity course = new CourseEntity(title, BigDecimal.valueOf(10.0), 60, id.intValue());
        ReflectionTestUtils.setField(course, "id", id);
        return course;
    }
}

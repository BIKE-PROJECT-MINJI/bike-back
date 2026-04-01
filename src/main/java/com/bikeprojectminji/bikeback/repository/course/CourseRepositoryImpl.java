package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class CourseRepositoryImpl implements CourseRepositoryCustom {

    private final EntityManager entityManager;

    public CourseRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<CourseEntity> findPageAfter(Long cursorId, int limitPlusOne) {
        if (cursorId == null) {
            TypedQuery<CourseEntity> query = entityManager.createQuery(
                    "select c from CourseEntity c order by c.displayOrder asc, c.id asc",
                    CourseEntity.class
            );
            query.setMaxResults(limitPlusOne);
            return query.getResultList();
        }

        Optional<CourseEntity> anchor = Optional.ofNullable(entityManager.find(CourseEntity.class, cursorId));
        if (anchor.isEmpty()) {
            return Collections.emptyList();
        }

        TypedQuery<CourseEntity> query = entityManager.createQuery(
                "select c from CourseEntity c " +
                        "where c.displayOrder > :displayOrder " +
                        "or (c.displayOrder = :displayOrder and c.id > :id) " +
                        "order by c.displayOrder asc, c.id asc",
                CourseEntity.class
        );
        query.setParameter("displayOrder", anchor.get().getDisplayOrder());
        query.setParameter("id", anchor.get().getId());
        query.setMaxResults(limitPlusOne);
        return query.getResultList();
    }
}

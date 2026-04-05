package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import java.util.List;

public interface CourseRepositoryCustom {

    List<CourseEntity> findPublicPageAfter(Long cursorId, int limitPlusOne);
}

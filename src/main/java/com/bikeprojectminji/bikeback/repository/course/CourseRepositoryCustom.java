package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import java.util.List;

public interface CourseRepositoryCustom {

    List<CourseEntity> findPageAfter(Long cursorId, int limitPlusOne);
}

package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;

public class CourseAccessPolicy {

    public void assertReadable(CourseEntity course, UserEntity currentUser, String shareToken) {
        if (course.getVisibility() == CourseVisibility.PUBLIC) {
            return;
        }
        if (course.getVisibility() == CourseVisibility.UNLISTED) {
            if (isOwner(course, currentUser) || isValidShareToken(course, shareToken)) {
                return;
            }
            throw new ForbiddenException("이 코스에 접근할 권한이 없습니다.");
        }
        if (isOwner(course, currentUser)) {
            return;
        }
        throw new ForbiddenException("이 코스는 공개되지 않았습니다.");
    }

    public void assertNotReportHidden(CourseEntity course, UserEntity currentUser) {
        if (!course.isReportHidden() || isOwner(course, currentUser)) {
            return;
        }
        throw new ForbiddenException("신고 누적으로 임시 숨김 처리된 코스입니다.");
    }

    public void assertOwned(CourseEntity course, Long ownerUserId) {
        if (course.getOwnerUserId() == null || !course.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("이 코스를 수정할 권한이 없습니다.");
        }
    }

    private boolean isOwner(CourseEntity course, UserEntity currentUser) {
        return currentUser != null
                && course.getOwnerUserId() != null
                && course.getOwnerUserId().equals(currentUser.getId());
    }

    private boolean isValidShareToken(CourseEntity course, String shareToken) {
        return shareToken != null
                && !shareToken.isBlank()
                && shareToken.equals(course.getShareToken());
    }
}

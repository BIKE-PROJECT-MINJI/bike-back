package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.beta.service.BetaAccessPolicy;
import com.bikeprojectminji.bikeback.course.dto.CoursePublicationResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CoursePublicationEntity;
import com.bikeprojectminji.bikeback.course.repository.CoursePublicationRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CoursePublicationService {

    private final AuthService authService;
    private final CourseRepository courseRepository;
    private final CoursePublicationRepository coursePublicationRepository;
    private final BetaAccessPolicy betaAccessPolicy;
    private final Clock clock;
    private final CourseAccessPolicy courseAccessPolicy = new CourseAccessPolicy();

    public CoursePublicationService(
            AuthService authService,
            CourseRepository courseRepository,
            CoursePublicationRepository coursePublicationRepository,
            BetaAccessPolicy betaAccessPolicy,
            Clock clock
    ) {
        this.authService = authService;
        this.courseRepository = courseRepository;
        this.coursePublicationRepository = coursePublicationRepository;
        this.betaAccessPolicy = betaAccessPolicy;
        this.clock = clock;
    }

    public CoursePublicationResponse publishCourse(String subject, Long courseId) {
        UserEntity user = authService.findUserBySubject(subject);
        betaAccessPolicy.assertBetaAccess(user);
        CourseEntity course = findOwnedCourse(courseId, user.getId());

        CoursePublicationEntity publication = coursePublicationRepository.findByCourseId(course.getId())
                .map(existingPublication -> {
                    existingPublication.publish(clock);
                    return existingPublication;
                })
                .orElseGet(() -> coursePublicationRepository.save(
                        new CoursePublicationEntity(course.getId(), user.getId(), clock)
                ));

        return toResponse(publication);
    }

    public CoursePublicationResponse unpublishCourse(String subject, Long courseId) {
        UserEntity user = authService.findUserBySubject(subject);
        betaAccessPolicy.assertBetaAccess(user);
        CourseEntity course = findOwnedCourse(courseId, user.getId());

        CoursePublicationEntity publication = coursePublicationRepository.findByCourseId(course.getId())
                .orElseThrow(() -> new NotFoundException("공개 게시 정보를 찾을 수 없습니다."));
        publication.unpublish(clock);
        return toResponse(publication);
    }

    private CourseEntity findOwnedCourse(Long courseId, Long ownerUserId) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        courseAccessPolicy.assertOwned(course, ownerUserId);
        return course;
    }

    private CoursePublicationResponse toResponse(CoursePublicationEntity publication) {
        return new CoursePublicationResponse(
                publication.getId(),
                publication.getCourseId(),
                publication.getOwnerUserId(),
                publication.getStatus().name(),
                publication.getPublishedAt(),
                publication.getUnpublishedAt()
        );
    }
}

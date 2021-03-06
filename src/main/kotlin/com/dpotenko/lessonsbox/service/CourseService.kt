package com.dpotenko.lessonsbox.service

import com.dpotenko.lessonsbox.Tables
import com.dpotenko.lessonsbox.Tables.COURSE
import com.dpotenko.lessonsbox.Tables.COURSE_ACCESS
import com.dpotenko.lessonsbox.Tables.GROUP_COURSE
import com.dpotenko.lessonsbox.domain.UserPrincipal
import com.dpotenko.lessonsbox.dto.AccessLevel
import com.dpotenko.lessonsbox.dto.CompletionDto
import com.dpotenko.lessonsbox.dto.CourseDto
import com.dpotenko.lessonsbox.dto.CourseType
import com.dpotenko.lessonsbox.dto.QuestionType
import com.dpotenko.lessonsbox.service.question.OptionParser
import com.dpotenko.lessonsbox.tables.pojos.CompletedLesson
import com.dpotenko.lessonsbox.tables.pojos.CompletedTest
import com.dpotenko.lessonsbox.tables.pojos.Course
import com.dpotenko.lessonsbox.tables.pojos.StartedCourse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.TableOnConditionStep
import org.jooq.impl.DSL
import org.jsoup.Jsoup
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

const val CSS_SELECTOR_CUSTOM_INPUTS = "select,input"


val viewCondition = DSL.or(COURSE.TYPE.eq(CourseType.DRAFT).and(COURSE_ACCESS.ACCESS_LEVEL.eq(AccessLevel.OWNER)), COURSE.TYPE.notEqual(CourseType.DRAFT))

val startCondition = DSL.or(COURSE.TYPE.eq(CourseType.DRAFT).and(COURSE_ACCESS.ACCESS_LEVEL.eq(AccessLevel.OWNER)),
        COURSE.TYPE.eq(CourseType.PUBLIC),
        COURSE.TYPE.eq(CourseType.PRIVATE).and(COURSE_ACCESS.ACCESS_LEVEL.eq(AccessLevel.OWNER).or(COURSE_ACCESS.ACCESS_LEVEL.eq(AccessLevel.STUDENT)))
)

val fromSupplier: (Long?) -> TableOnConditionStep<*> = { userId ->
    COURSE.leftJoin(COURSE_ACCESS).on(COURSE_ACCESS.COURSE_ID.eq(COURSE.ID).and(COURSE_ACCESS.USER_ID.eq(userId
            ?: 0).or(COURSE_ACCESS.USER_ID.isNull)))
}


@Component
class CourseService(val lessonService: LessonService,
                    val questionService: QuestionService,
                    val testService: TestService,
                    val variantService: VariantService,
                    val attachmentService: AttachmentService,
                    val ownerService: OwnerService,
                    val dslContext: DSLContext,
                    val optionParsers: List<OptionParser>) {
    fun saveCourse(dto: CourseDto): CourseDto {
        val course = dslContext.newRecord(COURSE, Course(dto.name, dto.category, dto.description, dto.id, false, dto.type, dto.key))

        if (dto.id == null) {
            course.insert()
        } else {
            course.update()
        }
        dto.id = course.id
        dto.lessons.forEach { lesson ->
            lesson.id = lessonService.saveLesson(lesson, course.id)
            lesson.attachments.forEach { attachmentDto ->
                attachmentDto.id = attachmentService.saveAttachment(attachmentDto, lesson.id!!)
            }
        }

        dto.tests.forEach { it.id = testService.saveTest(it, course.id) }
        dto.tests.forEach { testDto ->
            testDto.questions.forEach { questionDto ->
                if (questionDto.type == QuestionType.CUSTOM_INPUT) {
                    questionDto.variants = Jsoup.parse(questionDto.question).select(CSS_SELECTOR_CUSTOM_INPUTS)
                            .flatMap { input ->
                                optionParsers.find { input.tagName() == it.tagName }?.parseOptions(input) ?: emptyList()
                            }.toMutableList()

                    if (questionDto.id != null) {
                        val alreadySavedOptions = variantService.getVariantsByQuestionId(questionDto.id!!)
                        questionDto.variants
                                .forEach { optionToSave ->
                                    alreadySavedOptions.find { it.inputName == optionToSave.inputName && it.variant == optionToSave.variant }
                                            ?.let { optionToSave.id = it.id }
                                }
                    }
                }
                questionDto.id = questionService.saveQuestion(questionDto, testDto.id!!)
                questionDto.variants.forEach { variantDto ->
                    variantDto.id = variantService.saveVariant(variantDto, questionDto.id!!, questionDto.type)
                }
            }
        }

        lessonService.merge(dto.lessons, course.id)
        testService.merge(dto.tests, course.id)

        clearVariants(dto)

        return dto
    }


    fun getAllCourses(userPrincipal: UserPrincipal?): List<CourseDto> {
        val fields = COURSE.fields().toMutableList()
        fields.remove(COURSE.KEY)
        val courses = dslContext.select(*fields.toTypedArray())
                .from(fromSupplier.invoke(userPrincipal?.id))
                .where(COURSE.DELETED.eq(false).and(DSL.or(viewCondition, DSL.condition(ownerService.isSuperAdmin(userPrincipal)))))
                .fetchInto(Course::class.java)
        val dtos = courses.map {
            mapCourseToDto(it)
        }
        dtos.forEach { setCreators(it) }
        return dtos
    }

    fun getCoursesForGroup(userPrincipal: UserPrincipal?,
                           groupId: Long): List<CourseDto> {
        val fields = COURSE.fields().toMutableList()
        fields.remove(COURSE.KEY)
        val courses = dslContext.select(*fields.toTypedArray())
                .from(COURSE.join(GROUP_COURSE).on(GROUP_COURSE.COURSE_ID.eq(COURSE.ID)))
                .where(COURSE.DELETED.eq(false).and(GROUP_COURSE.GROUP_ID.eq(groupId)))
                .fetchInto(Course::class.java)
        val dtos = courses.map {
            mapCourseToDto(it)
        }
        dtos.forEach { setCreators(it) }
        return dtos
    }


    private fun setCreators(courseDto: CourseDto) {
        courseDto.ownerIds = ownerService.findCreators(courseDto.id!!).map { it.userId }
    }

    fun getCourseById(id: Long,
                      userPrincipal: UserPrincipal?,
                      key: String? = null): CourseDto {
        val userId = userPrincipal?.id

        val courseDto = getCourse(id, userPrincipal, key)
        courseDto.lessons = lessonService.getLessonsMetaByCourseId(courseDto.id!!)
        courseDto.tests = testService.getTestsByCourseId(id)

        runBlocking {
            for (test in courseDto.tests) {
                launch(Dispatchers.IO) {
                    if (userId?.let { testService.getCompletedTest(userId, test.id!!) != null } == true) {
                        test.isCompleted = true
                    }
                }
            }
        }

        userId?.let { markAsStarted(userId, courseDto.id!!) }
        courseDto.lessons.forEach { lesson ->
            if (userId?.let { lessonService.getCompletedLesson(userId, lesson.id!!) != null } == true) {
                lesson.isCompleted = true
            }
        }

        setCreators(courseDto)

        return courseDto
    }


    fun getCourseByIdForEdit(id: Long,
                             userPrincipal: UserPrincipal?): CourseDto {
        val dto = getCourseWithLessons(id, userPrincipal)

        dto.tests = testService.getFullTestsByCourseId(id)

        return dto
    }

    private fun getCourseWithLessons(id: Long,
                                     userPrincipal: UserPrincipal?,
                                     key: String? = null): CourseDto {
        val dto = getCourse(id, userPrincipal, key)

        dto.lessons = lessonService.getLessonsByCourseId(id)
        return dto
    }

    fun getCourse(id: Long,
                  userPrincipal: UserPrincipal?,
                  key: String? = null): CourseDto {
        val course = dslContext.selectFrom(COURSE)
                .where(COURSE.DELETED.eq(false).and(COURSE.ID.eq(id)))
                .fetchOneInto(Course::class.java)
        if (course == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This course does not exist")
        }

        val dto = mapCourseToDto(course)

        ownerService.checkAllowed(dto, userPrincipal, key)
        return dto
    }


    fun clearVariants(dto: CourseDto) {
        dto.tests.forEach { test ->
            test.questions.forEach { question ->
                if (question.type == QuestionType.CUSTOM_INPUT) {
                    question.variants = mutableListOf()
                }
            }
        }
    }

    private fun mapCourseToDto(course: Course): CourseDto {
        return CourseDto(
                course.name,
                course.description,
                course.category,
                emptyList(),
                emptyList(),
                course.id,
                mutableListOf(),
                course.type,
                course.key
        )
    }

    private fun markAsStarted(userId: Long,
                              courseId: Long) {
        val startedCourse = getStartedCourse(courseId, userId)

        if (startedCourse == null) {
            val toInsert = StartedCourse()
            toInsert.userId = userId
            toInsert.courseId = courseId
            val newRecord = dslContext.newRecord(Tables.STARTED_COURSE, toInsert)
            newRecord.insert()
        }

    }

    fun setCompletion(courseDto: CourseDto,
                      userId: Long) {
        val startedCourse = getStartedCourse(courseDto.id!!, userId)

        val lessons = lessonService.getLessonsMetaByCourseId(courseDto.id!!)
        val tests = testService.getFullTestsByCourseId(courseDto.id!!)

        val completedLessons = dslContext.selectFrom(Tables.LESSON.leftJoin(Tables.COMPLETED_LESSON).on(Tables.COMPLETED_LESSON.LESSON_ID.eq(Tables.LESSON.ID)))
                .where(Tables.LESSON.DELETED.eq(false).and(Tables.LESSON.COURSE_ID.eq(courseDto.id)).and(Tables.COMPLETED_LESSON.USER_ID.eq(userId)))
                .fetchInto(CompletedLesson::class.java)

        val completedTests = dslContext.selectFrom(Tables.COMPLETED_TEST.join(Tables.TEST).on(Tables.COMPLETED_TEST.TEST_ID.eq(Tables.TEST.ID)))
                .where(Tables.TEST.DELETED.eq(false).and(Tables.TEST.COURSE_ID.eq(courseDto.id)).and(Tables.COMPLETED_TEST.USER_ID.eq(userId)))
                .fetchInto(CompletedTest::class.java)

        val allLessonsCompleted = lessons.all { lessonDto -> completedLessons.find { it.lessonId == lessonDto.id } != null }
        val allTestsCompleted = tests.all { testDto -> completedTests.find { it.testId == testDto.id } != null }

        courseDto.completion = CompletionDto(
                startedCourse != null,
                allLessonsCompleted && allTestsCompleted && (lessons.isNotEmpty() || tests.isNotEmpty()),
                0.0,
                0.0
        )
    }

    private fun getStartedCourse(courseId: Long,
                                 userId: Long?): StartedCourse? {
        val startedCourse = dslContext.selectFrom(Tables.STARTED_COURSE.join(COURSE).on(Tables.STARTED_COURSE.COURSE_ID.eq(COURSE.ID)))
                .where(COURSE.DELETED.eq(false).and(COURSE.ID.eq(courseId))
                        .and(Tables.STARTED_COURSE.USER_ID.eq(userId)))
                .fetchOneInto(StartedCourse::class.java)
        return startedCourse
    }

    fun deleteCourseById(courseId: Long) {
        dslContext.update(COURSE)
                .set(COURSE.DELETED, true)
                .where(COURSE.ID.eq(courseId))
                .execute()
    }
}



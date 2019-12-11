package com.dpotenko.kirillweb.service

import com.dpotenko.kirillweb.Tables
import com.dpotenko.kirillweb.dto.QuestionStatus
import com.dpotenko.kirillweb.dto.TestDto
import com.dpotenko.kirillweb.dto.VariantDto
import com.dpotenko.kirillweb.tables.pojos.Test
import org.jooq.DSLContext
import org.springframework.stereotype.Component

@Component
class TestService(val dslContext: DSLContext,
                  val questionService: QuestionService) {

    fun saveTest(dto: TestDto,
                 courseId: Long): Long {
        val record = dslContext.newRecord(Tables.TEST, Test(dto.id, dto.name, dto.order.toInt(), courseId, false))
        if (dto.id == null) {
            record.insert()
        } else {
            record.update()
        }

        return record.id
    }

    fun merge(tests: List<TestDto>,
              courseId: Long) {
        dslContext.selectFrom(Tables.TEST)
                .where(Tables.TEST.COURSE_ID.eq(courseId).and(Tables.TEST.DELETED.eq(false)))
                .fetch()
                .forEach { testRecord ->
                    val foundTest = tests.find { it.id == testRecord.id }
                    if (foundTest == null) {
                        testRecord.deleted = true
                        testRecord.store()
                    } else {
                        questionService.merge(foundTest.questions, foundTest.id!!)
                    }
                }
    }

    fun getTestsByCourseId(courseId: Long): List<TestDto> {
        val tests = dslContext.selectFrom(Tables.TEST)
                .where(Tables.TEST.COURSE_ID.eq(courseId).and(Tables.TEST.DELETED.eq(false)))
                .fetchInto(Test::class.java)

        val testsDto = tests.map { mapTestToDto(it) }

        testsDto.forEach { testDto -> testDto.questions = questionService.getQuestionsByTestId(testDto.id!!) }

        return testsDto
    }

    fun checkTest(userTestDto: TestDto): TestDto {
        val test = dslContext.selectFrom(Tables.TEST)
                .where(Tables.TEST.ID.eq(userTestDto.id).and(Tables.TEST.DELETED.eq(false)))
                .fetchOneInto(Test::class.java)

        val testDto = mapTestToDto(test)
        testDto.questions = questionService.getQuestionsByTestId(testDto.id!!)

        userTestDto.questions.forEach { userQuestion ->
            testDto.questions.find { questionDto -> questionDto.id == userQuestion.id }?.let {
                if (checkAnsweredCorrectly(userQuestion.variants, it.variants)) {
                    userQuestion.status = QuestionStatus.SUCCESS
                } else {
                    userQuestion.status = QuestionStatus.FAILED
                }
            }
        }

        return userTestDto
    }

    private fun checkAnsweredCorrectly(userVariants: List<VariantDto>,
                                       actualVariants: List<VariantDto>): Boolean {
        var result = true
        userVariants.forEach { userVariant ->
            val foundActualVariant = actualVariants.find { it.id == userVariant.id }
            foundActualVariant?.let { actualVariant ->
                if (userVariant.isTicked && actualVariant.isRight) {
                    userVariant.isRight = true
                } else if (actualVariant.isRight) {
                    result = false
                    userVariant.isRight = true
                    userVariant.isTicked = true
                } else if (userVariant.isTicked) {
                    result = false
                    userVariant.isWrong = true
                    userVariant.isTicked = true
                }
            }
        }

        return result
    }

    private fun mapTestToDto(test: Test): TestDto {
        return TestDto(
                listOf(),
                test.name,
                test.orderNumber.toLong(),
                test.id
        )
    }
}
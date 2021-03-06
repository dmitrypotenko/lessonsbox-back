package com.dpotenko.lessonsbox.dto

data class CourseDto(
        val name: String,
        val description: String?,
        val category: String?,
        var lessons: List<LessonDto>,
        var tests: List<TestDto>,
        var id: Long?,
        var ownerIds: List<Long>?,
        val type: CourseType,
        val key: String?
) {
    var completion: CompletionDto? = null
}


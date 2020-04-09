package com.dpotenko.kirillweb.controller

import com.dpotenko.kirillweb.domain.UserPrincipal
import com.dpotenko.kirillweb.dto.TestDto
import com.dpotenko.kirillweb.service.TestService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.websocket.server.PathParam

@RestController
@RequestMapping("/tests")
class TestController(val testService: TestService) {
    @PostMapping("/check")
    fun checkTest(@RequestBody testDto: TestDto, @AuthenticationPrincipal userPrincipal: UserPrincipal?): ResponseEntity<TestDto> {
        val checkedTest = testService.checkTest(testDto)
        checkedTest.isCompleted = true
        userPrincipal?.let { testService.markAsCompleted(testDto, it.id) }
        return ResponseEntity.ok(checkedTest)
    }

    @PostMapping("/invalidate/{testId}")
    fun invalidateTest(@PathVariable("testId") testId: Long, @AuthenticationPrincipal userPrincipal: UserPrincipal?): ResponseEntity<Unit> {
        userPrincipal?.let { testService.invalidateTest(testId, it.id) }
        return ResponseEntity.ok().build<Unit>()
    }
}
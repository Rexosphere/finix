package org.finix.kernel.web

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.MissingServletRequestParameterException

/**
 * A caller who forgets a required query parameter has made a client error, and the response has to
 * say so. Before this handler existed the exception fell through to the catch-all and every such
 * request was answered with a 500 — telling the caller to retry something that could never succeed,
 * logging a full stack trace each time, and counting against the service's server-error rate.
 */
class GlobalExceptionHandlerTest : StringSpec({

    val handler = GlobalExceptionHandler()

    fun request(uri: String = "/lite/balance") = MockHttpServletRequest("GET", uri)

    "missing query parameter is a 400, not a 500" {
        val problem = handler.onMissingParameter(
            MissingServletRequestParameterException("phone", "String"),
            request(),
        )

        problem.status shouldBe HttpStatus.BAD_REQUEST.value()
    }

    "missing query parameter names the parameter so the caller can fix the call" {
        val problem = handler.onMissingParameter(
            MissingServletRequestParameterException("phone", "String"),
            request(),
        )

        problem.properties?.get("parameter") shouldBe "phone"
        problem.detail shouldBe "Query parameter 'phone' is required"
    }

    "missing query parameter carries the standard problem envelope" {
        val problem = handler.onMissingParameter(
            MissingServletRequestParameterException("phone", "String"),
            request("/lite/balance"),
        )

        problem.type.toString() shouldBe "${GlobalExceptionHandler.PROBLEM_TYPE_BASE}/missing-parameter"
        problem.title shouldBe "Required parameter missing"
        problem.properties?.get("code") shouldBe "missing-parameter"
        problem.properties?.get("path") shouldBe "/lite/balance"
        // The correlation handle is what turns a user-reported error into a trace.
        problem.instance shouldNotBe null
    }
})

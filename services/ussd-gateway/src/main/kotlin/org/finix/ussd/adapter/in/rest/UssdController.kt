package org.finix.ussd.adapter.`in`.rest

import org.finix.ussd.application.usecase.HandleUssdUseCase
import org.finix.ussd.domain.UssdDirectory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Africa's Talking compatible USSD callback.
 *
 * Telcos POST `application/x-www-form-urlencoded` with sessionId/phoneNumber/text/serviceCode.
 * The web simulator posts the same fields as JSON for convenience.
 */
@RestController
@RequestMapping
class UssdController(
    private val handleUssd: HandleUssdUseCase,
) {

    @PostMapping(
        path = ["/ussd", "/api/v1/ussd"],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
        produces = [MediaType.TEXT_PLAIN_VALUE],
    )
    fun ussdForm(
        @RequestParam sessionId: String,
        @RequestParam phoneNumber: String,
        @RequestParam(required = false) serviceCode: String?,
        @RequestParam(required = false, defaultValue = "") text: String,
    ): String {
        @Suppress("UnusedPrivateMember")
        val ignored = serviceCode
        return handleUssd.execute(sessionId, phoneNumber, text)
    }

    @PostMapping(
        path = ["/ussd", "/api/v1/ussd"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.TEXT_PLAIN_VALUE],
    )
    fun ussdJson(@RequestBody body: UssdJsonRequest): String {
        val sid = body.sessionId ?: error("sessionId required")
        val phone = body.phoneNumber ?: error("phoneNumber required")
        return handleUssd.execute(sid, phone, body.text.orEmpty())
    }

    /** Zero-JS `/lite` balance lookup — returns plain HTML under the 50 KB budget. */
    @GetMapping(path = ["/lite/balance"], produces = [MediaType.TEXT_HTML_VALUE])
    fun liteBalance(@RequestParam phone: String): String {
        val subscriber = UssdDirectory.findByPhone(phone)
        if (subscriber == null) {
            return html("FINIX Lite", "<p>Phone not registered.</p><p><a href=\"/lite.html\">Back</a></p>")
        }
        val reply = handleUssd.execute(
            sessionId = "lite-${System.currentTimeMillis()}",
            phoneNumber = phone,
            text = "1",
        )
        val body = reply.removePrefix("END ").removePrefix("CON ")
        return html(
            "FINIX Lite",
            """
            <h1>FINIX</h1>
            <p class="phone">${UssdDirectory.normalize(phone)}</p>
            <pre>$body</pre>
            <p><a href="/lite.html">Back</a> · <a href="/ussd.html">USSD</a></p>
            """.trimIndent(),
        )
    }

    private fun html(title: String, body: String): String = """
        <!DOCTYPE html>
        <html lang="en"><head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width,initial-scale=1"/>
        <title>$title</title>
        <style>
        body{margin:0;font-family:Georgia,serif;background:#f3efe6;color:#1a1a1a;padding:1.5rem}
        h1{font-size:1.75rem;margin:0 0 .5rem;letter-spacing:.04em}
        .phone{color:#3d5a45;font-family:ui-monospace,monospace}
        pre{background:#fff;border:1px solid #cfc6b6;padding:1rem;white-space:pre-wrap}
        a{color:#1f6b45}
        </style>
        </head><body>$body</body></html>
    """.trimIndent()
}

data class UssdJsonRequest(
    val sessionId: String? = null,
    val phoneNumber: String? = null,
    val serviceCode: String? = null,
    val text: String? = null,
    val networkCode: String? = null,
)

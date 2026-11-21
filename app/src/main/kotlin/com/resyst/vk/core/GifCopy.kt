package com.resyst.vk.core

import com.resyst.vk.R

/**
 * r11b: the words the GIF search shows, in one place so the keyboard's disclosure, the settings
 * dialog and the error lines say exactly the same thing (GO2, GE1, GT1). r14b: resources (`gif_*`), asked
 * for in the language of the phone when drawn.
 */
object GifCopy {
    fun title(t: Txt) = t.get(R.string.gif_title)
    fun setting(t: Txt) = t.get(R.string.ctl_gif_search)
    fun placeholder(t: Txt) = t.get(R.string.gif_placeholder)
    fun attribution(t: Txt) = t.get(R.string.gif_attribution)

    /** The OFF explainer in the GIF tab. */
    fun offLine1(t: Txt) = t.get(R.string.gif_off_line1)
    fun offLine2(t: Txt) = t.get(R.string.gif_off_line2)
    fun offButton(t: Txt) = t.get(R.string.gif_off_button)

    /** GO2: what is sent, to whom, what never is, where it is recorded, how to turn it off. */
    fun disclosure(t: Txt): List<String> = listOf(
        t.get(R.string.gif_disclosure_1), t.get(R.string.gif_disclosure_2),
        t.get(R.string.gif_disclosure_3), t.get(R.string.gif_disclosure_4),
    )

    fun accept(t: Txt) = t.get(R.string.gif_accept)
    fun cancel(t: Txt) = t.get(R.string.gif_cancel)

    fun loading(t: Txt) = t.get(R.string.gif_loading)
    fun empty(t: Txt) = t.get(R.string.gif_empty)
    fun notAccepted(t: Txt) = t.get(R.string.gif_not_accepted)
    fun tooBig(t: Txt) = t.get(R.string.gif_too_big)

    /** One calm line per failure (GE1); the user retries by tapping, never automatically. */
    enum class Fail {
        OFFLINE,
        TIMEOUT,
        RATE_LIMIT,
        HTTP,
        REDIRECT,
        BAD_RESPONSE,
        TOO_LARGE,
        TLS,
        /** The user left the tab / the field while it was in flight: logged, never shown. */
        CANCELLED,
        ;

        /** The line the panel shows (`gif_fail_<name>`); empty for [CANCELLED]. */
        fun line(t: Txt): String = if (this == CANCELLED) "" else t.need("gif_fail_" + name.lowercase())

        /** The words in the connection log (`gif_faillog_<name>`). */
        fun log(t: Txt): String = t.need("gif_faillog_" + name.lowercase())

        companion object {
            /** HTTP status → failure (null = 200). 3xx is a refused redirect (GA2). */
            fun ofStatus(code: Int): Fail? = when {
                code == 200 -> null
                code == 429 -> RATE_LIMIT
                code in 300..399 -> REDIRECT
                else -> HTTP
            }
        }
    }

    /**
     * A page entry's outcome for the book, stored as a token ([ConnectionLog.Outcome]) and written in the phone's
     * language when shown: what was asked (the query, or trending), the page, and what came back.
     */
    fun pageOutcome(query: String?, page: Int, count: Int, fail: Fail?, status: Int = 0): String =
        ConnectionLog.Outcome.of("gifpage", page, count, status, fail?.name?.lowercase() ?: "", query ?: "")
}

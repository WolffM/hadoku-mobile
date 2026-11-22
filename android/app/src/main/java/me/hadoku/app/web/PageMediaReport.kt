package me.hadoku.app.web

import org.json.JSONException
import org.json.JSONObject

/**
 * What PageMediaPlugin's script says about the page: whether anything audible
 * is playing, and what to call it. It comes from a remote page, so anything
 * that is not exactly that shape is dropped and the title is cut to a line.
 */
data class PageMediaReport(val playing: Boolean, val title: String) {
    companion object {
        const val MAX_TITLE = 200

        fun parse(data: String?): PageMediaReport? {
            if (data == null) return null
            val json = try {
                JSONObject(data)
            } catch (e: JSONException) {
                return null
            }
            val playing = json.opt("playing") as? Boolean ?: return null
            val title = (json.opt("title") as? String).orEmpty()
                .replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)
            return PageMediaReport(playing, title)
        }
    }
}

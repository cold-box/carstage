package com.carstage.app.gleo

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Gleo voice actions. Gleo starts the app with the function name as the intent action and expects
 * a reply broadcast in its callback format (Pleos docs: app-integration-guide/guide/examples and
 * action_callback). Action names must match `assets/schema.json`, the manifest intent filter and [all].
 */
object GleoActions {
    const val OPEN_STAGE = "OPEN_STAGE"
    const val START_BATTLE = "START_BATTLE"
    const val FINISH_SONG = "FINISH_SONG"

    val all = setOf(OPEN_STAGE, START_BATTLE, FINISH_SONG)

    enum class Status(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail"), ERROR("error") }

    /** How Gleo gives the answer (action_callback's `mode`): reads [reply]'s message out, or only beeps. */
    enum class Mode(val wire: String) { VOICE("voice"), BEEP("beep") }

    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    /** The Gleo examples page uses ACTION_PARAM and the radio use case uses actionParameters. */
    private val PARAM_EXTRAS = listOf("ACTION_PARAM", "actionParameters")

    fun isGleoAction(intent: Intent?): Boolean = intent?.action in all

    /**
     * Reads the action parameters. The examples send a JSON object that is itself wrapped as a JSON
     * string, so a string result is parsed once more. No parameter extra means no parameters;
     * anything else that is not a JSON object is a failure, so a wire-format change is not hidden.
     */
    fun parameters(intent: Intent): Result<JSONObject> = runCatching {
        val key = PARAM_EXTRAS.firstOrNull { intent.hasExtra(it) } ?: return Result.success(JSONObject())
        // The value type is what is being checked, so the untyped getter is needed here.
        @Suppress("DEPRECATION")
        val raw = intent.extras?.get(key)
        val text = raw as? String ?: error("$key is ${raw?.javaClass?.name}, expected a String")
        if (text.isBlank()) return Result.success(JSONObject())
        when (val value = JSONTokener(text.trim()).nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> error("$key is not a JSON object: $text")
        }
    }

    /** Gleo may read [message] aloud, so it is written in Korean. */
    fun reply(context: Context, functionName: String, status: Status, message: String, mode: Mode) {
        val response = JSONObject()
            .put("functionName", functionName)
            .put("status", status.wire)
            .put("message", message)
            .put("mode", mode.wire)
            // The callback format defines data as a JSON array serialized into a string.
            .put("data", JSONArray().toString())
        context.sendBroadcast(
            Intent(RESPONSE_ACTION)
                .putExtra("result", status.wire)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString())
        )
    }
}

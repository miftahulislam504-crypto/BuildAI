package com.buildx.ai.llm

import org.json.JSONArray
import org.json.JSONObject

/** How a slot's value is checked before it reaches an action. */
enum class Kind { TEXT, NUMBER, CHOICE }

/**
 * One entry of an action's params map, as the model sees it.
 * [max]: for NUMBER the largest allowed value, for TEXT the longest allowed text (0 = the default length).
 */
data class Slot(
    val name: String,
    val description: String,
    val kind: Kind = Kind.TEXT,
    val choices: List<String> = emptyList(),
    val min: Int = 0,
    val max: Int = 0,
    val required: Boolean = false
)

/** One thing the model may ask the phone to do. [name] is the name of a registered action. */
data class Tool(val name: String, val description: String, val slots: List<Slot> = emptyList(), val agent: Boolean = false)

/**
 * The menu handed to the model with every request, and the allow-list its answer is checked against.
 * No Android classes, so it can be unit tested. The model never sees or runs anything not listed here:
 * "unknown", "echo", "cancel" and "end_call" are deliberately absent.
 */
object LlmTools {

    private fun text(name: String, description: String, required: Boolean = false) =
        Slot(name, description, Kind.TEXT, required = required)

    private fun num(name: String, description: String, min: Int, max: Int) =
        Slot(name, description, Kind.NUMBER, min = min, max = max)

    private fun pick(name: String, description: String, vararg choices: String, required: Boolean = false) =
        Slot(name, description, Kind.CHOICE, choices.toList(), required = required)

    private val target = text(
        "target",
        "Contact name or family relation exactly as the user said it, in the user's own words and script. " +
            "Never translate or transliterate it. Omit it if the user only gave a number."
    )
    private val number = text("number", "Digits of a phone number. Only if the user actually said digits; never invent one.")

    val all: List<Tool> = listOf(
        Tool("call", "Place an ordinary phone call to a saved contact or a phone number.", listOf(target, number)),
        Tool(
            "whatsapp_call", "Place a WhatsApp call (a voice call unless video is set).",
            listOf(target, number, pick("video", "Give 1 for a video call. Omit for a voice call.", "1"))
        ),
        Tool(
            "take_photo", "Open the camera and take a photo after a short countdown.",
            listOf(pick("camera", "front for a selfie, back otherwise.", "front", "back"))
        ),
        Tool(
            "alarm", "Set an alarm for a clock time, or a countdown timer. Give hour (and minute) OR in_minutes, never both.",
            listOf(
                num("hour", "Hour in 24-hour time (7 pm is 19).", 0, 23),
                num("minute", "Minute, 0 if the user gave none.", 0, 59),
                num("in_minutes", "Length of a countdown timer in minutes.", 1, 1440),
                pick("when", "Which day, only if the user said. Omit otherwise.", "auto", "today", "tomorrow", "day_after")
            )
        ),
        Tool(
            "flashlight", "Turn the flashlight (torch) on or off.",
            listOf(pick("state", "on, off, or toggle if the user did not say.", "on", "off", "toggle"))
        ),
        Tool(
            "wifi", "Turn Wi-Fi on or off. 'open' only opens the Wi-Fi settings panel.",
            listOf(pick("state", "on, off or open.", "on", "off", "open"))
        ),
        Tool(
            "bluetooth", "Turn Bluetooth on or off. 'open' only opens the Bluetooth settings.",
            listOf(pick("state", "on, off or open.", "on", "off", "open"))
        ),
        Tool(
            "volume", "Change the media volume.",
            listOf(
                pick("action", "What to do.", "up", "down", "set", "mute", "unmute", required = true),
                num("level", "Target percent, only with action=set.", 0, 100),
                num("delta", "Percent step, only with up or down. Omit for the default step.", 1, 100)
            )
        ),
        Tool(
            "brightness", "Change the screen brightness.",
            listOf(
                pick("action", "What to do.", "up", "down", "set", required = true),
                num("level", "Target percent, only with action=set.", 0, 100),
                num("delta", "Percent step, only with up or down. Omit for the default step.", 1, 100)
            )
        ),
        Tool(
            "ringer", "Switch the phone to silent, vibrate or normal ringing.",
            listOf(pick("mode", "The ringer mode.", "silent", "vibrate", "normal", required = true))
        ),
        Tool(
            "open_app", "Open an installed app by its name.",
            listOf(text("app", "App name as the user said it, for example YouTube, Foodpanda or বিকাশ.", required = true))
        ),
        Tool(
            "media", "Control the music or video that is playing.",
            listOf(pick("action", "What to do.", "play", "pause", "next", "prev", required = true))
        ),
        Tool(
            "swipe", "Swipe to the next or previous Reel or Short on screen.",
            listOf(pick("dir", "Which way.", "next", "prev", required = true))
        ),
        Tool(
            "youtube_play", "Search YouTube and play the first result.",
            listOf(text("query", "What to play: a song, artist or video title exactly as the user said it. Omit if they named nothing."))
        ),
        Tool(
            "open_feed", "Open YouTube Shorts or Instagram Reels.",
            listOf(pick("feed", "Which one.", "shorts", "reels", required = true))
        ),
        Tool(
            "navigate", "Start Google Maps navigation to a place.",
            listOf(text("query", "The destination exactly as the user said it.", required = true))
        ),
        Tool(
            "web_search", "Search the web with Google.",
            listOf(text("query", "What to search for.", required = true))
        ),
        Tool(
            "calc", "Work out a spoken arithmetic expression.",
            listOf(
                text(
                    "expr",
                    "Plain digits and the words plus, minus, times, divided, point, separated by spaces. " +
                        "Example: 12 times 5 plus 3. The decimal 2.5 is written 2 point 5.",
                    required = true
                )
            )
        ),
        Tool("time", "Say the current time."),
        Tool("date", "Say today's date and weekday."),
        Tool("battery", "Say the battery level and whether the phone is charging."),
        Tool("screen_lock", "Lock the screen."),
        Tool("screen_wake", "Turn the screen on. It cannot unlock the phone."),
        Tool("back", "Press the Back button (go back one screen)."),
        Tool("home", "Go to the home screen."),
        Tool("recents", "Show the recent apps."),
        Tool("notifications", "Pull down the notification panel."),
        Tool("screenshot", "Take a screenshot."),
        Tool("read_screen", "Read out the text that is on the screen right now."),
        Tool(
            "tap", "Tap the button, link or item on the current screen whose label matches the words.",
            listOf(Slot("text", "The visible label of what to tap, exactly as the user said it.", Kind.TEXT, max = 80, required = true))
        ),
        Tool(
            "type_text", "Type words into the text field that is currently focused on screen. Not for passwords.",
            listOf(Slot("text", "The words to type, exactly as the user said them.", Kind.TEXT, max = 300, required = true))
        ),
        Tool(
            "ui_task",
            "Do a multi-step job by operating the screen: look at it, tap, type and scroll until done. " +
                "Use only when no single tool above does it, for example sending a message inside an app. " +
                "If the app is not open yet, call open_app first, in the same plan.",
            listOf(Slot("goal", "What the user wants done, as one clear sentence, with names and message text exactly as said.", Kind.TEXT, max = 300, required = true)),
            agent = true
        )
    )

    private val byName = all.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    /** The tools in the shape the proxy forwards to the model. Screen-operating tools only when [agent] is on. */
    fun toJson(agent: Boolean = false): JSONArray = toolsJson(all.filter { agent || !it.agent })

    /** name, description, JSON-schema input for each tool */
    fun toolsJson(tools: List<Tool>): JSONArray {
        val out = JSONArray()
        for (t in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (s in t.slots) {
                val p = JSONObject().put("description", s.description)
                when (s.kind) {
                    Kind.TEXT -> p.put("type", "string")
                    Kind.NUMBER -> p.put("type", "integer").put("minimum", s.min).put("maximum", s.max)
                    Kind.CHOICE -> p.put("type", "string").put("enum", JSONArray(s.choices))
                }
                props.put(s.name, p)
                if (s.required) required.put(s.name)
            }
            val schema = JSONObject().put("type", "object").put("properties", props)
            if (required.length() > 0) schema.put("required", required)
            out.put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", schema))
        }
        return out
    }
}

package com.carstage.app

import com.carstage.app.audio.EchoOff
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What kind of thing a notice says (docs/10 5장 "색", 7장 "안내 문구"), from the least to the most
 * serious: what the app did or is doing, what the user should watch or do, and what went wrong.
 * The screen colors the line by the most serious kind on it.
 */
enum class NoticeTone { INFO, CAUTION, ERROR }

/** The notice line as the screen shows it: the text and its most serious kind. */
data class ShownNotice(val text: String, val tone: NoticeTone)

/** [current] with [line] added after it, once: a notice from one part of the app does not hide another's. */
internal fun withNotice(current: String?, line: String): String = when {
    current.isNullOrEmpty() -> line
    line in current -> current
    else -> "$current $line"
}

/** [current] with [line] first and once: a notice that matters most while it lasts. */
internal fun withNoticeFirst(current: String?, line: String): String =
    listOfNotNull(line, withoutNotice(current, line)).joinToString(" ")

/** [current] without [line], whatever else it holds; null when nothing is left. */
internal fun withoutNotice(current: String?, line: String): String? =
    current?.replace(line, "")?.replace(Regex(" {2,}"), " ")?.trim()?.ifEmpty { null }

/**
 * The line of Korean the first screen shows about what did not go as planned or what the app is
 * doing for the user. An event the user should see at once replaces the line ([show]); a state
 * that lasts adds its own sentence and takes it out again ([add], [remove]), so it does not hide
 * another part's sentence. Thread-safe.
 */
class NoticeBoard {
    private val line = MutableStateFlow<String?>(null)

    /**
     * The kind of every text put on the line, kept by text: the few texts the app has are put up
     * again and again, so this stays small. Written before the line changes, so a reader of the
     * line finds the kinds of what it holds. [toneOf] finds a text in the line by containment, so a
     * short text must not be part of a longer one of another kind (a text of one kind with another
     * appended is put up whole, with the more serious kind), and a text is always given the same kind.
     */
    private val tones = ConcurrentHashMap<String, NoticeTone>().apply {
        put(SILENT_MIC, NoticeTone.CAUTION)
        put(NOT_CONNECTED, NoticeTone.CAUTION)
    }

    /** What the screen shows; null for nothing. */
    val notice: StateFlow<String?> = line.asStateFlow()

    /** The line itself, for [ResumeOffers], which gives way only to the exact stop notice it set. */
    internal val flow: MutableStateFlow<String?> get() = line

    /** Replaces whatever is shown with [text], of kind [tone]. */
    fun show(text: String, tone: NoticeTone) {
        tones[text] = tone
        line.value = text
    }

    /** Adds [text], of kind [tone], after what is shown. */
    fun add(text: String, tone: NoticeTone) {
        tones[text] = tone
        line.update { withNotice(it, text) }
    }

    fun remove(text: String) = line.update { withoutNotice(it, text) }

    /** The microphone went silent ([silent]) or came back: its notice comes first while it lasts, then only it goes. */
    fun silence(silent: Boolean) = line.update { if (silent) withNoticeFirst(it, SILENT_MIC) else withoutNotice(it, SILENT_MIC) }

    /**
     * A stage opened: a new one shows what the last recovery found ([recovery]) and whether the car
     * could not be reached ([connection]) in place of what was shown, keeping the battery, night,
     * control and echo cancelling notices its own stage may already have added from another thread; one already open
     * adds them to what it shows.
     */
    fun opened(alreadyOpen: Boolean, recovery: String?, connection: String?) = line.update { shown ->
        if (alreadyOpen) {
            listOfNotNull(recovery, connection).fold(shown) { notice, text -> withNotice(notice, text) }
        } else {
            val kept = listOf(BatterySaver.LOW_NOTICE, NightDimming.NOTICE, CONTROL_DENIED, EchoOff.NOTICE_LOST).filter { shown?.contains(it) == true }
            (listOfNotNull(recovery, connection) + kept).joinToString(" ").ifEmpty { null }
        }
    }

    /**
     * The most serious kind among the texts put on the line that [text] holds; an error for text
     * the board was never given, as every notice was shown as one before kinds existed.
     */
    fun toneOf(text: String): NoticeTone = tones.entries.filter { it.key in text }.maxOfOrNull { it.value } ?: NoticeTone.ERROR

    companion object {
        const val NOT_CONNECTED = "차에 연결하지 못해서 화면에서만 반응해요."

        /** The car refused some or all control to this app (no permission); see [com.carstage.app.stage.ControlDenials]. */
        const val CONTROL_DENIED = "차가 허용하지 않은 기능은 화면에서만 반응해요."
        const val NO_ACCESS_SETTINGS = "이 차에서는 곡 정보 설정을 열 수 없어요."
        const val CALL_STOP = "전화가 와서 무대를 멈췄어요."
        const val CHARGE_DONE = "충전이 끝나서 마지막 곡으로 무대를 마쳤어요."
        const val DUEL_FAILED = "대결을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."

        /** Gleo asked the basic flow for a stage with no song playing (docs/13). */
        const val PICK_FIRST = "부를 노래를 화면에서 골라 주세요."

        /** Gleo asked the basic mode for a duel, which the duel mode has (docs/13). */
        const val DUEL_IN_ALL = "대결은 화면에서 대결을 고르면 할 수 있어요."
        const val SILENT_MIC = "마이크 소리가 들어오지 않아요. 마이크가 꺼져 있거나 다른 앱이 쓰고 있는지 확인해 주세요."
        const val MIC_NOT_ALLOWED =
            "마이크를 허용해야 노래에 반응할 수 있어요. 화면에서 노래 시작을 누르거나 설정 > 앱 > CarStage에서 마이크를 허용해 주세요."
        const val RESTORE_FAILED = "일부 차량 설정을 원래대로 되돌리지 못했어요. 차량 설정에서 확인해 주세요."
        const val RESTORE_AFTER_PARKING = "주행 중에는 바꿀 수 없는 차량 설정이 있어서, 정차하면 원래대로 되돌릴게요."
        const val RECOVERY_GAVE_UP = "지난 무대에서 바뀐 차량 설정을 되돌리지 못했어요. 차량 설정에서 확인해 주세요."
    }
}

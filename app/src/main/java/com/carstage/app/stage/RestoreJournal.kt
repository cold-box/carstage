package com.carstage.app.stage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * The car's original values for the targets an open stage has changed, written to disk before
 * each target's first change. If the process ends without closing the stage (crash, force stop,
 * low-memory kill, power loss), the next start restores them from here.
 *
 * Values are the output's raw values as text (for example `AUTOMATIC` or `16777215`), so they can
 * be written back exactly. Pure JVM, so it can be tested with a temporary directory.
 */
class RestoreJournal(private val file: File) {
    /** Records [raw] as [target]'s original, unless one is already recorded; throws [IOException] when it cannot be saved. */
    @Synchronized
    fun record(target: CabinTarget, raw: String) {
        val values = load()
        val key = keyOf(target)
        if (values.containsKey(key)) return
        values[key] = raw
        save(values)
    }

    /** The recorded originals; keys this version does not know are skipped. Throws [IOException] when the file cannot be read. */
    @Synchronized
    fun entries(): Map<CabinTarget, String> = load().mapNotNull { (key, raw) -> targetOf(key)?.let { it to raw } }.toMap()

    /** Forgets [targets] only, for example the ones a stage restored at close; other entries stay. */
    @Synchronized
    fun forget(targets: Set<CabinTarget>) {
        val values = load()
        val kept = values.filterKeys { key -> targetOf(key)?.let { it !in targets } ?: true }
        if (kept.size == values.size) return
        if (kept.isEmpty()) clear() else save(kept)
    }

    /**
     * Forgets everything, once the stage closed or the originals were restored. If the file
     * cannot be deleted it is emptied instead, so stale originals are never applied later.
     */
    @Synchronized
    fun clear() {
        if (file.exists() && !file.delete()) save(emptyMap())
    }

    private fun load(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames().associateWithTo(mutableMapOf()) { properties.getProperty(it) }
    }

    /** Writes a temporary file, syncs it to the disk and renames it over the journal, so a crash never leaves half a file. */
    private fun save(values: Map<String, String>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { out ->
            Properties().apply { putAll(values) }.store(out, "CarStage: original car values to restore")
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("cannot replace $file")
    }

    companion object {
        fun keyOf(target: CabinTarget): String = when (target) {
            CabinTarget.AmbientSwitch -> "AmbientSwitch"
            CabinTarget.AmbientColor -> "AmbientColor"
            CabinTarget.AmbientBrightness -> "AmbientBrightness"
            is CabinTarget.ReadingLight -> "ReadingLight.${target.side.name}"
            CabinTarget.Fan -> "Fan"
            is CabinTarget.SeatVentilation -> "SeatVentilation.${target.side.name}"
            is CabinTarget.Temperature -> "Temperature.${target.side.name}"
        }

        fun targetOf(key: String): CabinTarget? {
            val side = key.substringAfter('.', "").let { name -> Side.entries.firstOrNull { it.name == name } }
            return when (key.substringBefore('.')) {
                "AmbientSwitch" -> CabinTarget.AmbientSwitch
                "AmbientColor" -> CabinTarget.AmbientColor
                "AmbientBrightness" -> CabinTarget.AmbientBrightness
                "ReadingLight" -> side?.let { CabinTarget.ReadingLight(it) }
                "Fan" -> CabinTarget.Fan
                "SeatVentilation" -> side?.let { CabinTarget.SeatVentilation(it) }
                "Temperature" -> side?.let { CabinTarget.Temperature(it) }
                else -> null
            }
        }
    }
}

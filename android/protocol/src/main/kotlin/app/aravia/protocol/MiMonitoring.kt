package app.aravia.protocol

/**
 * Health monitoring settings (docs/CAPABILITIES.md §9.1-9.3): Command{8, GET} returns
 * health(10).<setting>; Command{8, GET + 1} writes the same message back with only known fields changed.
 */
object MiMonitoring {
    enum class Setting(val getSubtype: Int, val field: Int) {
        SPO2(8, 7),
        HEART_RATE(10, 8),
        STRESS(14, 10);

        companion object {
            fun fromGetSubtype(subtype: Long): Setting? = entries.firstOrNull { it.getSubtype.toLong() == subtype }
        }
    }

    fun get(setting: Setting): ByteArray = pb { varint(1, 8); varint(2, setting.getSubtype) }.data

    /** The setting's raw bytes in the band's reply to a GET, as returned. */
    fun reply(c: PbMessage, setting: Setting): ByteArray? = c.message(10)?.blobs?.get(setting.field)

    fun set(setting: Setting, body: ByteArray): ByteArray =
        pb { varint(1, 8); varint(2, setting.getSubtype + 1); message(10, pb { bytes(setting.field, body) }) }.data

    // Heart rate: {disabled 1, interval 2 (minutes; 0 smart), ..., advancedMonitoring 5, unknown7 7, ...}

    sealed class HeartRateMode {
        object Off : HeartRateMode() { override fun toString() = "off" }
        object Smart : HeartRateMode() { override fun toString() = "smart" }
        data class Every(val minutes: Int) : HeartRateMode() { override fun toString() = "every($minutes)" }
    }

    fun heartRateMode(body: ByteArray): HeartRateMode? {
        val f = PbFields.parse(body) ?: return null
        if (f.varint(1) == 1L) return HeartRateMode.Off
        val interval = (f.varint(2) ?: 0L).toInt()
        return if (interval == 0) HeartRateMode.Smart else HeartRateMode.Every(interval)
    }

    /** Sleep heart rate monitoring ("advanced monitoring"): advancedMonitoring 5{enabled 1}. */
    fun sleepHeartRate(body: ByteArray): Boolean? =
        PbFields.parse(body)?.bytes(5)?.let { PbFields.parse(it) }?.varint(1)?.let { it == 1L }

    fun heartRate(body: ByteArray, mode: HeartRateMode? = null, sleep: Boolean? = null): ByteArray? {
        val f = PbFields.parse(body) ?: return null
        when (mode) {
            is HeartRateMode.Off -> f.set(1, 1L)
            is HeartRateMode.Smart -> { f.set(1, 0L); f.set(2, 0L) }
            is HeartRateMode.Every -> { f.set(1, 0L); f.set(2, mode.minutes.toLong()) }
            null -> {}
        }
        val advanced = f.bytes(5)?.let { PbFields.parse(it) } ?: PbFields()
        if (sleep != null) advanced.set(1, if (sleep) 1L else 0L)
        else if (advanced.varint(1) == null) advanced.set(1, 0L)
        f.set(5, advanced.data)
        if (f.varint(7) == null) f.set(7, 1L)
        // breathingScore 9, irregularRhythmAlert 10, sleepRespiratoryRate 11: 0 means unsupported, omit on SET.
        for (number in 9..11) if (f.varint(number) == 0L) f.remove(number)
        return f.data
    }

    // SpO2: {unknown1 1 = 1, mode 2 (0 off, 1 during sleep, 2 all day), alarmLow 4}

    fun spo2Mode(body: ByteArray): Int? = PbFields.parse(body)?.let { (it.varint(2) ?: 0L).toInt() }

    fun spo2(body: ByteArray, mode: Int): ByteArray? {
        val f = PbFields.parse(body) ?: return null
        if (f.varint(1) == null) f.set(1, 1L)
        f.set(2, mode.toLong())
        return f.data
    }

    // Stress: {allDayTracking 1, relaxReminder 2}

    fun stressAllDay(body: ByteArray): Boolean? = PbFields.parse(body)?.let { it.varint(1) == 1L }

    fun stress(body: ByteArray, allDay: Boolean): ByteArray? {
        val f = PbFields.parse(body) ?: return null
        f.set(1, if (allDay) 1L else 0L)
        return f.data
    }
}

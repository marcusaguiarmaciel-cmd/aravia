package app.aravia.protocol

import java.io.ByteArrayOutputStream

/** Minimal proto2 writer: only what the commands need. */
class PbWriter {
    private val out = ByteArrayOutputStream()
    val data: ByteArray get() = out.toByteArray()

    fun varint(field: Int, value: Long): PbWriter { key(field, 0); putVarint(value); return this }
    fun varint(field: Int, value: Int): PbWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): PbWriter {
        key(field, 2); putVarint(value.size.toLong()); out.write(value); return this
    }

    /** sint32/sint64: zigzag varint. */
    fun sint(field: Int, value: Long): PbWriter = varint(field, (value shl 1) xor (value shr 63))

    /** double: wire type 1, little-endian. */
    fun double(field: Int, value: Double): PbWriter {
        key(field, 1)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, value: PbWriter) = bytes(field, value.data)

    private fun key(field: Int, wire: Int) = putVarint((field.toLong() shl 3) or wire.toLong())

    private fun putVarint(value: Long) {
        var v = value
        while (v.toULong() >= 0x80uL) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

inline fun pb(build: PbWriter.() -> Unit): PbWriter = PbWriter().apply(build)

/** Minimal proto2 reader: the last varint and the last length-delimited value per field. */
class PbMessage private constructor(
    val varints: Map<Int, Long>,
    val blobs: Map<Int, ByteArray>,
    val fieldNumbers: List<Int>,
) {
    fun message(field: Int): PbMessage? = blobs[field]?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): PbMessage? {
            val varints = mutableMapOf<Int, Long>()
            val blobs = mutableMapOf<Int, ByteArray>()
            val fields = mutableListOf<Int>()
            var i = 0
            fun readVarint(): Long? {
                var result = 0L
                var shift = 0
                while (i < data.size && shift < 64) {
                    val x = data[i].toInt() and 0xFF
                    i++
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
                return null
            }
            while (i < data.size) {
                val key = readVarint() ?: return null
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> varints[field] = readVarint() ?: return null
                    1 -> { if (data.size - i < 8) return null; i += 8 }
                    2 -> {
                        val n = readVarint() ?: return null
                        if (n < 0 || n > data.size - i) return null
                        blobs[field] = data.copyOfRange(i, i + n.toInt())
                        i += n.toInt()
                    }
                    5 -> { if (data.size - i < 4) return null; i += 4 }
                    else -> return null
                }
                fields.add(field)
            }
            return PbMessage(varints, blobs, fields)
        }
    }
}

/**
 * A protobuf message kept as its fields in wire order. A setting the band returned goes back with
 * only the known fields changed and every other byte intact (docs/CAPABILITIES.md §1.1).
 */
class PbFields private constructor(private val items: MutableList<Field>) {
    data class Field(
        val number: Int,
        /** 0 varint, 1 fixed64, 2 length-delimited, 5 fixed32. */
        val wire: Int,
        val varint: Long = 0L,
        val bytes: ByteArray = ByteArray(0),
    )

    constructor() : this(mutableListOf())

    fun varint(number: Int): Long? = items.firstOrNull { it.number == number && it.wire == 0 }?.varint

    fun bytes(number: Int): ByteArray? = items.firstOrNull { it.number == number && it.wire == 2 }?.bytes

    fun set(number: Int, varint: Long) = put(Field(number = number, wire = 0, varint = varint))

    fun set(number: Int, bytes: ByteArray) = put(Field(number = number, wire = 2, bytes = bytes))

    fun remove(number: Int) { items.removeAll { it.number == number } }

    private fun put(field: Field) {
        val at = items.indexOfFirst { it.number == field.number }
        if (at >= 0) items[at] = field else items.add(field)
    }

    val data: ByteArray
        get() {
            val out = ByteArrayOutputStream()
            fun putVarint(value: Long) {
                var v = value
                while (v.toULong() >= 0x80uL) {
                    out.write(((v and 0x7F) or 0x80).toInt())
                    v = v ushr 7
                }
                out.write(v.toInt())
            }
            for (field in items) {
                putVarint((field.number.toLong() shl 3) or field.wire.toLong())
                when (field.wire) {
                    0 -> putVarint(field.varint)
                    2 -> { putVarint(field.bytes.size.toLong()); out.write(field.bytes) }
                    else -> out.write(field.bytes)
                }
            }
            return out.toByteArray()
        }

    companion object {
        fun parse(data: ByteArray): PbFields? {
            val out = mutableListOf<Field>()
            var i = 0
            fun readVarint(): Long? {
                var result = 0L
                var shift = 0
                while (i < data.size && shift < 64) {
                    val x = data[i].toInt() and 0xFF
                    i++
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
                return null
            }
            fun take(n: Int): ByteArray? {
                if (n < 0 || data.size - i < n) return null
                val slice = data.copyOfRange(i, i + n)
                i += n
                return slice
            }
            while (i < data.size) {
                val key = readVarint() ?: return null
                val number = (key ushr 3).toInt()
                if (number <= 0) return null
                val wire = (key and 7L).toInt()
                val field = when (wire) {
                    0 -> Field(number, 0, varint = readVarint() ?: return null)
                    1 -> Field(number, 1, bytes = take(8) ?: return null)
                    2 -> {
                        val n = readVarint() ?: return null
                        if (n < 0 || n > data.size - i) return null
                        Field(number, 2, bytes = take(n.toInt()) ?: return null)
                    }
                    5 -> Field(number, 5, bytes = take(4) ?: return null)
                    else -> return null
                }
                out.add(field)
            }
            return PbFields(out)
        }
    }
}


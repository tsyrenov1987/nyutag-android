// Нютаг. QR-код ссылки приглашения: свой кодировщик по ISO/IEC 18004, без сторонних библиотек (на iOS его рисует
// Core Image, на Android встроенного нет). Только то, что нужно приглашению: байтовый режим, уровень коррекции H
// (середину закрывает знак Нютага), версии 1–10 — до 119 байт, ссылке хватает с запасом.

package chat.nyutag.core.nyutag

class QrCode private constructor(val version: Int) {
    /** Сторона в модулях, без белой рамки. */
    val size: Int = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    /** Тёмный ли модуль: `x` — столбец, `y` — строка. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    var mask: Int = 0
        private set

    companion object {
        private const val MAX_VERSION = 10
        // Уровень H, версии 1–10: байт коррекции на блок и число блоков.
        private val ECC_PER_BLOCK = intArrayOf(0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28)
        private val BLOCKS = intArrayOf(0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8)
        private const val FORMAT_H = 2

        /** Код для текста; `null` — не влезает в версию 10. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { 4 + countBits(it) + data.size * 8 <= dataCodewords(it) * 8 } ?: return null
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            put(0b0100, 4)
            put(data.size, countBits(version))
            for (b in data) put(b.toInt() and 0xFF, 8)
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(capacity / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }.toByte() }
            return QrCode(version).apply { build(codewords) }
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        internal fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(version: Int): Int = rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        // Рид — Соломон над GF(256) с многочленом 0x11D.
        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }
    }

    private fun build(data: ByteArray) {
        drawFunctionPatterns()
        drawCodewords(withEcc(data))
        // Маска с наименьшим штрафом: так код легче читается камерой.
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (m in 0 until 8) {
            applyMask(m)
            drawFormat(m)
            val penalty = penalty()
            if (penalty < bestPenalty) { best = m; bestPenalty = penalty }
            applyMask(m)
        }
        mask = best
        applyMask(best)
        drawFormat(best)
    }

    private fun set(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        val align = alignmentPositions()
        val last = align.size - 1
        for (i in align.indices) for (j in align.indices) {
            if (i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0) continue
            for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
        drawFormat(0)
        drawVersion()
    }

    private fun finder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val xx = x + dx
            val yy = y + dy
            if (xx !in 0 until size || yy !in 0 until size) continue
            val dist = maxOf(Math.abs(dx), Math.abs(dy))
            set(xx, yy, dist != 2 && dist != 4)
        }
    }

    private fun alignmentPositions(): List<Int> {
        if (version == 1) return emptyList()
        val count = version / 7 + 2
        val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val result = ArrayList<Int>()
        var pos = size - 7
        repeat(count - 1) { result.add(0, pos); pos -= step }
        result.add(0, 6)
        return result
    }

    private fun drawFormat(mask: Int) {
        val data = FORMAT_H shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
        for (i in 9..14) set(14 - i, 8, bit(i))
        for (i in 0..7) set(size - 1 - i, 8, bit(i))
        for (i in 8..14) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = version shl 12 or rem
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark)
            set(b, a, dark)
        }
    }

    /** Блоки данных с байтами коррекции, перемежённые по стандарту. */
    private fun withEcc(data: ByteArray): ByteArray {
        val blocks = BLOCKS[version]
        val eccLen = ECC_PER_BLOCK[version]
        val raw = rawDataModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLen = raw / blocks
        val div = divisor(eccLen)
        val list = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until blocks) {
            val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + len)
            k += len
            val block = ByteArray(shortLen + 1)
            System.arraycopy(dat, 0, block, 0, len)
            // У коротких блоков на месте недостающего байта данных — пустышка, при перемежении её пропускаем.
            System.arraycopy(remainder(dat, div), 0, block, shortLen + 1 - eccLen, eccLen)
            list.add(block)
        }
        val result = ArrayList<Byte>(raw)
        for (i in 0 until shortLen + 1) for (j in list.indices) {
            if (i != shortLen - eccLen || j >= shortBlocks) result.add(list[j][i])
        }
        return result.toByteArray()
    }

    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!isFunction[y][x] && i < data.size * 8) {
                    modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            if (!isFunction[y][x] && maskBit(mask, x, y)) modules[y][x] = !modules[y][x]
        }
    }

    /** Штраф по четырём правилам стандарта: длинные ряды, квадраты 2×2, ложные искатели, перекос тёмного. */
    internal fun penalty(): Int {
        var result = 0
        val finderLike = listOf(booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false),
                                booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true))
        for (pass in 0..1) {
            for (a in 0 until size) {
                fun at(b: Int) = if (pass == 0) modules[a][b] else modules[b][a]
                var run = 1
                for (b in 1..size) {
                    if (b < size && at(b) == at(b - 1)) {
                        run++
                    } else {
                        if (run >= 5) result += 3 + run - 5
                        run = 1
                    }
                }
                for (b in 0..size - 11) {
                    if (finderLike.any { p -> p.indices.all { at(b + it) == p[it] } }) result += 40
                }
            }
        }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        return result + k * 10
    }
}

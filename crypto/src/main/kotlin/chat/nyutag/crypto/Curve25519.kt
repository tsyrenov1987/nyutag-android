// Нютаг. X25519 (RFC 7748) и Ed25519 (RFC 8032) своими руками: в JCA Android 11 этих кривых нет, а чужие
// библиотеки мы не берём. X25519 — на java.math.BigInteger (нужен редко, при новых сессиях). Точки Ed25519 —
// на 16 лимбах по 16 бит, как в TweetNaCl: проверка подписи идёт на каждую шифрованную реплику, и на
// BigInteger она стоила на телефоне ~100 мс.

package chat.nyutag.crypto

import java.math.BigInteger

// BigInteger.TWO появился в Android только с API 33.
private val TWO: BigInteger = BigInteger.valueOf(2)
private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)

private fun BigInteger.modP(): BigInteger = mod(P)
private fun mulP(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P)
private fun invP(a: BigInteger): BigInteger = a.modPow(P - TWO, P)

/** 32 байта little-endian → число. */
private fun ByteArray.toFieldElement(mask255: Boolean): BigInteger {
    val copy = copyOf()
    if (mask255) copy[31] = (copy[31].toInt() and 0x7F).toByte()
    return BigInteger(1, copy.reversedArray())
}

/** Число → 32 байта little-endian. */
private fun BigInteger.toLittleEndian32(): ByteArray {
    val be = toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
    val out = ByteArray(32)
    for (i in be.indices) out[be.size - 1 - i] = be[i]
    return out
}

object Curve25519 {
    val basePoint: ByteArray = ByteArray(32).also { it[0] = 9 }

    private val A24 = BigInteger.valueOf(121665)

    private fun clamp(scalar: ByteArray): BigInteger {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return BigInteger(1, k.reversedArray())
    }

    /** Лестница Монтгомери по RFC 7748, раздел 5. */
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32 && point.size == 32)
        val k = clamp(scalar)
        val u = point.toFieldElement(mask255 = true)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = k.testBit(t).let { if (it) 1 else 0 }
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit
            val a = (x2 + z2).modP()
            val aa = mulP(a, a)
            val b = (x2 - z2).modP()
            val bb = mulP(b, b)
            val e = (aa - bb).modP()
            val c = (x3 + z3).modP()
            val d = (x3 - z3).modP()
            val da = mulP(d, a)
            val cb = mulP(c, b)
            val daPlusCb = (da + cb).modP()
            val daMinusCb = (da - cb).modP()
            x3 = mulP(daPlusCb, daPlusCb)
            z3 = mulP(u, mulP(daMinusCb, daMinusCb))
            x2 = mulP(aa, bb)
            z2 = mulP(e, (aa + mulP(A24, e)).modP())
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return mulP(x2, invP(z2)).toLittleEndian32()
    }
}

/** Элемент поля 2^255 − 19: 16 знаковых лимбов по 16 бит, младший первым. */
private class Fe(val v: LongArray = LongArray(16)) {
    fun copy(): Fe = Fe(v.copyOf())

    companion object {
        fun of(n: BigInteger): Fe = unpack(n.toLittleEndian32())

        fun unpack(bytes: ByteArray): Fe {
            val o = Fe()
            for (i in 0 until 16) o.v[i] = (bytes[2 * i].toLong() and 0xFF) + ((bytes[2 * i + 1].toLong() and 0xFF) shl 8)
            o.v[15] = o.v[15] and 0x7FFF
            return o
        }
    }
}

private fun carry(o: LongArray) {
    for (i in 0 until 16) {
        o[i] += 1L shl 16
        val c = o[i] shr 16
        if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
        o[i] -= c shl 16
    }
}

/** Обмен p и q при b = 1 без ветвления. */
private fun select(p: LongArray, q: LongArray, b: Int) {
    val c = (b - 1).toLong().inv()
    for (i in 0 until 16) {
        val t = c and (p[i] xor q[i])
        p[i] = p[i] xor t
        q[i] = q[i] xor t
    }
}

private fun pack(n: Fe): ByteArray {
    val t = n.v.copyOf()
    val m = LongArray(16)
    carry(t); carry(t); carry(t)
    repeat(2) {
        m[0] = t[0] - 0xFFED
        for (i in 1 until 15) {
            m[i] = t[i] - 0xFFFF - ((m[i - 1] shr 16) and 1)
            m[i - 1] = m[i - 1] and 0xFFFF
        }
        m[15] = t[15] - 0x7FFF - ((m[14] shr 16) and 1)
        val b = ((m[15] shr 16) and 1).toInt()
        m[14] = m[14] and 0xFFFF
        select(t, m, 1 - b)
    }
    val o = ByteArray(32)
    for (i in 0 until 16) {
        o[2 * i] = (t[i] and 0xFF).toByte()
        o[2 * i + 1] = (t[i] shr 8).toByte()
    }
    return o
}

private fun parity(a: Fe): Int = pack(a)[0].toInt() and 1
private fun equal(a: Fe, b: Fe): Boolean = pack(a).contentEquals(pack(b))
private fun add(a: Fe, b: Fe): Fe = Fe(LongArray(16) { a.v[it] + b.v[it] })
private fun sub(a: Fe, b: Fe): Fe = Fe(LongArray(16) { a.v[it] - b.v[it] })

private fun mul(a: Fe, b: Fe): Fe {
    val t = LongArray(31)
    val x = a.v
    val y = b.v
    for (i in 0 until 16) {
        val xi = x[i]
        for (j in 0 until 16) t[i + j] += xi * y[j]
    }
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    val o = t.copyOf(16)
    carry(o); carry(o)
    return Fe(o)
}

private fun square(a: Fe): Fe = mul(a, a)

/** a^(p − 2). */
private fun invert(a: Fe): Fe {
    var c = a.copy()
    for (k in 253 downTo 0) {
        c = square(c)
        if (k != 2 && k != 4) c = mul(c, a)
    }
    return c
}

/** a^((p − 5) / 8). */
private fun pow2523(a: Fe): Fe {
    var c = a.copy()
    for (k in 250 downTo 0) {
        c = square(c)
        if (k != 1) c = mul(c, a)
    }
    return c
}

object Ed25519 {
    private val D: BigInteger = mulP(BigInteger.valueOf(-121665).modP(), invP(BigInteger.valueOf(121666)))
    private val L: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    private val ZERO = Fe()
    private val ONE = Fe().also { it.v[0] = 1 }
    private val FD = Fe.of(D)
    private val FD2 = Fe.of((D + D).modP())
    private val FI = Fe.of(SQRT_M1)

    /** Точка в расширенных координатах: x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: Fe, val y: Fe, val z: Fe, val t: Fe) {
        fun add(o: Point): Point {
            val a = mul(sub(y, x), sub(o.y, o.x))
            val b = mul(add(x, y), add(o.x, o.y))
            val c = mul(mul(t, o.t), FD2)
            val d0 = mul(z, o.z)
            val d = add(d0, d0)
            val e = sub(b, a)
            val f = sub(d, c)
            val g = add(d, c)
            val h = add(b, a)
            return Point(mul(e, f), mul(h, g), mul(g, f), mul(e, h))
        }

        /** Удвоение (dbl-2008-hwcd, a = −1): дешевле сложения точки с собой. */
        fun double(): Point {
            val a = square(x)
            val b = square(y)
            val zz = square(z)
            val c = add(zz, zz)
            val e = sub(sub(square(add(x, y)), a), b)
            val g = sub(b, a)
            val f = sub(g, c)
            val h = sub(sub(ZERO, a), b)
            return Point(mul(e, f), mul(g, h), mul(f, g), mul(e, h))
        }

        fun encode(): ByteArray {
            val zi = invert(z)
            val out = pack(mul(y, zi))
            out[31] = (out[31].toInt() xor (parity(mul(x, zi)) shl 7)).toByte()
            return out
        }

        /** Скаляр `scalar` (32 байта little-endian) на точку: лестница с обменом без ветвлений. */
        fun multiply(scalar: ByteArray): Point {
            val p = arrayOf(ZERO.copy(), ONE.copy(), ONE.copy(), ZERO.copy())
            val q = arrayOf(x.copy(), y.copy(), z.copy(), t.copy())
            for (i in 255 downTo 0) {
                val b = (scalar[i / 8].toInt() shr (i and 7)) and 1
                for (k in 0 until 4) select(p[k].v, q[k].v, b)
                val sum = Point(q[0], q[1], q[2], q[3]).add(Point(p[0], p[1], p[2], p[3]))
                val twice = Point(p[0], p[1], p[2], p[3]).let { it.add(it) }
                q[0] = sum.x; q[1] = sum.y; q[2] = sum.z; q[3] = sum.t
                p[0] = twice.x; p[1] = twice.y; p[2] = twice.z; p[3] = twice.t
                for (k in 0 until 4) select(p[k].v, q[k].v, b)
            }
            return Point(p[0], p[1], p[2], p[3])
        }
    }

    private val BASE: Point = run {
        val y = mulP(BigInteger.valueOf(4), invP(BigInteger.valueOf(5)))
        val y2 = mulP(y, y)
        val x2 = mulP((y2 - BigInteger.ONE).modP(), invP((mulP(D, y2) + BigInteger.ONE).modP()))
        var x = x2.modPow((P + BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (mulP(x, x) != x2) x = mulP(x, SQRT_M1)
        if (x.testBit(0)) x = (P - x).modP()
        Point(Fe.of(x), Fe.of(y), ONE.copy(), Fe.of(mulP(x, y)))
    }

    /** Точка по кодировке RFC 8032 (5.1.3); `null` — не точка кривой или кодировка не каноническая. */
    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        if (bytes.toFieldElement(mask255 = true) >= P) return null
        val sign = (bytes[31].toInt() shr 7) and 1
        val y = Fe.unpack(bytes)
        val y2 = square(y)
        val u = sub(y2, ONE)
        val v = add(mul(y2, FD), ONE)
        val v2 = square(v)
        val v3 = mul(v2, v)
        val v7 = mul(square(v3), v)
        var x = mul(mul(pow2523(mul(v7, u)), u), v3)
        if (!equal(mul(square(x), v), u)) x = mul(x, FI)
        if (!equal(mul(square(x), v), u)) return null
        if (sign == 1 && equal(x, ZERO)) return null
        if (parity(x) != sign) x = sub(ZERO, x)
        return Point(x, y, ONE.copy(), mul(x, y))
    }

    class ExpandedKey internal constructor(internal val scalar: BigInteger, internal val prefix: ByteArray, val publicKey: ByteArray)

    fun expand(seed: ByteArray): ExpandedKey {
        require(seed.size == 32)
        val h = Hash.sha512(seed)
        val s = h.copyOfRange(0, 32)
        s[0] = (s[0].toInt() and 248).toByte()
        s[31] = (s[31].toInt() and 63).toByte()
        s[31] = (s[31].toInt() or 64).toByte()
        val scalar = BigInteger(1, s.reversedArray())
        return ExpandedKey(scalar, h.copyOfRange(32, 64), BASE.multiply(s).encode())
    }

    private fun scalarFromHash(vararg parts: ByteArray): BigInteger {
        val digest = java.security.MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return BigInteger(1, digest.digest().reversedArray()).mod(L)
    }

    fun sign(key: ExpandedKey, message: ByteArray): ByteArray {
        val r = scalarFromHash(key.prefix, message)
        val rEncoded = BASE.multiply(r.toLittleEndian32()).encode()
        val k = scalarFromHash(rEncoded, key.publicKey, message)
        val s = (r + k * key.scalar).mod(L)
        return rEncoded + s.toLittleEndian32()
    }

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        decode(rBytes) ?: return false
        val sBytes = signature.copyOfRange(32, 64)
        if (BigInteger(1, sBytes.reversedArray()) >= L) return false
        val k = scalarFromHash(rBytes, publicKey, message)
        // [s]B = R + [k]A  ⇔  кодировка [s]B + [k](−A) совпадает с R. Данные открытые, поэтому без постоянного
        // времени: оба скаляра одним проходом (Штраус), общие удвоения.
        val minusA = Point(sub(ZERO, a.x), a.y, a.z, sub(ZERO, a.t))
        val table = arrayOf(BASE, minusA, BASE.add(minusA))
        val kBytes = k.toLittleEndian32()
        var check = Point(ZERO, ONE, ONE, ZERO)
        for (i in 255 downTo 0) {
            check = check.double()
            val index = ((sBytes[i / 8].toInt() shr (i and 7)) and 1) or (((kBytes[i / 8].toInt() shr (i and 7)) and 1) shl 1)
            if (index != 0) check = check.add(table[index - 1])
        }
        return check.encode().contentEquals(rBytes)
    }
}

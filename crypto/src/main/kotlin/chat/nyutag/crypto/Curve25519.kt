// Нютаг. X25519 (RFC 7748) и Ed25519 (RFC 8032) своими руками на java.math.BigInteger: в JCA Android 11
// этих кривых нет, а чужие библиотеки мы не берём. Арифметика BigInteger не за постоянное время —
// для телефона это принято осознанно; при нужде заменяется на лимбы без смены интерфейса.

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

object Ed25519 {
    private val D: BigInteger = mulP(BigInteger.valueOf(-121665).modP(), invP(BigInteger.valueOf(121666)))
    private val D2: BigInteger = (D + D).modP()
    private val L: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /** Точка в расширенных координатах: x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        fun add(o: Point): Point {
            val a = mulP((y - x).modP(), (o.y - o.x).modP())
            val b = mulP((y + x).modP(), (o.y + o.x).modP())
            val c = mulP(mulP(t, D2), o.t)
            val d = mulP((z + z).modP(), o.z)
            val e = (b - a).modP()
            val f = (d - c).modP()
            val g = (d + c).modP()
            val h = (b + a).modP()
            return Point(mulP(e, f), mulP(g, h), mulP(f, g), mulP(e, h))
        }

        fun double(): Point {
            val a = mulP(x, x)
            val b = mulP(y, y)
            val c = (mulP(z, z) * TWO).modP()
            val xy = (x + y).modP()
            val e = (mulP(xy, xy) - a - b).modP()
            val g = (b - a).modP()      // D + B, где D = -A
            val f = (g - c).modP()
            val h = (a + b).negate().modP() // D - B
            return Point(mulP(e, f), mulP(g, h), mulP(f, g), mulP(e, h))
        }

        fun multiply(scalar: BigInteger): Point {
            var result = IDENTITY
            var base = this
            for (i in 0 until scalar.bitLength()) {
                if (scalar.testBit(i)) result = result.add(base)
                base = base.double()
            }
            return result
        }

        fun encode(): ByteArray {
            val zi = invP(z)
            val ax = mulP(x, zi)
            val ay = mulP(y, zi)
            val out = ay.toLittleEndian32()
            if (ax.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun isIdentityEncoding(o: Point): Boolean = encode().contentEquals(o.encode())
    }

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val BASE: Point = run {
        val y = mulP(BigInteger.valueOf(4), invP(BigInteger.valueOf(5)))
        val x = recoverX(y, sign = false) ?: error("базовая точка")
        Point(x, y, BigInteger.ONE, mulP(x, y))
    }

    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = mulP(y, y)
        val u = (y2 - BigInteger.ONE).modP()
        val v = (mulP(D, y2) + BigInteger.ONE).modP()
        val x2 = mulP(u, invP(v))
        var x = x2.modPow((P + BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (mulP(x, x) != x2) {
            x = mulP(x, SQRT_M1)
            if (mulP(x, x) != x2) return null
        }
        if (x == BigInteger.ZERO && sign) return null
        if (x.testBit(0) != sign) x = (P - x).modP()
        return x
    }

    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = bytes[31].toInt() and 0x80 != 0
        val y = bytes.toFieldElement(mask255 = true)
        if (y >= P) return null
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, mulP(x, y))
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
        return ExpandedKey(scalar, h.copyOfRange(32, 64), BASE.multiply(scalar).encode())
    }

    private fun scalarFromHash(vararg parts: ByteArray): BigInteger {
        val digest = java.security.MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return BigInteger(1, digest.digest().reversedArray()).mod(L)
    }

    fun sign(key: ExpandedKey, message: ByteArray): ByteArray {
        val r = scalarFromHash(key.prefix, message)
        val rEncoded = BASE.multiply(r).encode()
        val k = scalarFromHash(rEncoded, key.publicKey, message)
        val s = (r + k * key.scalar).mod(L)
        return rEncoded + s.toLittleEndian32()
    }

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val r = decode(signature.copyOfRange(0, 32)) ?: return false
        val s = BigInteger(1, signature.copyOfRange(32, 64).reversedArray())
        if (s >= L) return false
        val k = scalarFromHash(signature.copyOfRange(0, 32), publicKey, message)
        val left = BASE.multiply(s)
        val right = r.add(a.multiply(k))
        return left.isIdentityEncoding(right)
    }
}

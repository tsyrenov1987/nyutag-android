// Нютаг. Описание зашифрованного вложения (`file` в содержимом реплики): адрес, ключ AES-CTR, iv и хеш шифртекста.
// Формат по спецификации Matrix (EncryptedFile v2), общий со всеми клиентами. Само шифрование — в слое медиа.

package chat.nyutag.core.media

import chat.nyutag.json.JsonValue

data class EncryptedFileKey(
    val kty: String = "oct",
    val keyOps: List<String> = listOf("encrypt", "decrypt"),
    val alg: String = "A256CTR",
    /** Ключ в base64url без `=`. */
    val k: String,
    val ext: Boolean = true,
) {
    val json: JsonValue get() = JsonValue.of("kty" to kty, "key_ops" to keyOps, "alg" to alg, "k" to k, "ext" to ext)

    companion object {
        fun from(json: JsonValue?): EncryptedFileKey? {
            val kty = json?.get("kty")?.string ?: return null
            val ops = json["key_ops"]?.array?.mapNotNull { it.string } ?: return null
            val alg = json["alg"]?.string ?: return null
            val k = json["k"]?.string ?: return null
            val ext = json["ext"]?.bool ?: return null
            return EncryptedFileKey(kty, ops, alg, k, ext)
        }
    }
}

data class EncryptedFile(
    val url: String,
    val key: EncryptedFileKey,
    /** Unpadded base64, 16 байт: 8 случайных + 8 нулевых (нулевая половина — счётчик блоков CTR). */
    val iv: String,
    /** `{"sha256": <unpadded base64 от шифртекста>}`. */
    val hashes: Map<String, String>,
    val v: String = "v2",
) {
    val mxc: MXC? get() = MXC.parse(url)

    val json: JsonValue get() = JsonValue.of("url" to url, "key" to key.json, "iv" to iv, "hashes" to hashes, "v" to v)

    companion object {
        /** Строго по формату: любого обязательного поля нет — `null`. */
        fun from(json: JsonValue?): EncryptedFile? {
            val url = json?.get("url")?.string ?: return null
            val key = EncryptedFileKey.from(json["key"]) ?: return null
            val iv = json["iv"]?.string ?: return null
            val hashesJson = json["hashes"]?.obj ?: return null
            val hashes = LinkedHashMap<String, String>()
            for ((name, value) in hashesJson) hashes[name] = value.string ?: return null
            val v = json["v"]?.string ?: return null
            return EncryptedFile(url, key, iv, hashes, v)
        }
    }
}

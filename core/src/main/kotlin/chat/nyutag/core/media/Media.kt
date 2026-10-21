// Нютаг. Адрес файла на сервере `mxc://сервер/идентификатор`.

package chat.nyutag.core.media

data class MXC(val server: String, val mediaID: String) {
    override fun toString(): String = "mxc://$server/$mediaID"

    companion object {
        const val PREFIX = "mxc://"

        /** `null`, если строка не адрес: нет префикса, нет сервера или идентификатора, лишний `/`. */
        fun parse(string: String): MXC? {
            if (!string.startsWith(PREFIX)) return null
            val rest = string.substring(PREFIX.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val mediaID = rest.substring(slash + 1)
            if (mediaID.isEmpty() || mediaID.contains('/')) return null
            return MXC(rest.substring(0, slash), mediaID)
        }
    }
}

enum class ThumbnailMethod(val rawValue: String) { CROP("crop"), SCALE("scale") }

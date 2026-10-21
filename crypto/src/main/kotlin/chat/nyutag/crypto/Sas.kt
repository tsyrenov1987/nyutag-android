// Нютаг. Короткая строка подтверждения (SAS, `m.sas.v1`): общий секрет X25519 двух устройств даёт семь эмодзи
// или три числа, которые люди сверяют глазами, и ключи MAC (`hkdf-hmac-sha256.v2`) для обмена подписями ключей.

package chat.nyutag.crypto

/** Одна сторона SAS до обмена ключами: свежая пара X25519 на каждую проверку. */
class Sas(internal val keyPair: Curve25519KeyPair) {
    constructor() : this(Curve25519KeyPair())
    constructor(privateKey: ByteArray) : this(Curve25519KeyPair(privateKey))

    val publicKeyBase64: String get() = keyPair.publicKeyBase64

    fun establish(theirPublicKey: String): EstablishedSas = EstablishedSas(keyPair.sharedSecret(Base64.decode(theirPublicKey)))
}

/** SAS после обмена ключами. */
class EstablishedSas(internal val secret: ByteArray) {
    fun bytes(info: String, count: Int = 6): ByteArray = Hash.hkdfSha256(secret, ByteArray(0), info.toByteArray(), count)

    private fun value(info: String): Long = bytes(info).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    /** Семь номеров эмодзи: первые 42 бита по 6. */
    fun emojiIndices(info: String): List<Int> {
        val v = value(info)
        return (0 until 7).map { ((v shr (42 - 6 * it)) and 0x3F).toInt() }
    }

    /** Три числа 1000…9191: первые 39 бит по 13. */
    fun decimals(info: String): List<Int> {
        val v = value(info)
        return (0 until 3).map { ((v shr (35 - 13 * it)) and 0x1FFF).toInt() + 1000 }
    }

    /** MAC строки ключом, выведенным из общего секрета по `info`. */
    fun calculateMac(input: String, info: String): String {
        val key = Hash.hkdfSha256(secret, ByteArray(0), info.toByteArray(), 32)
        return Base64.encodeUnpadded(Hash.hmacSha256(key, input.toByteArray()))
    }
}

/** Таблица эмодзи из спецификации Matrix (`sas-emoji.json`) с её же русскими названиями. */
object SasEmoji {
    data class Entry(val emoji: String, val name: String, val nameRU: String)

    val table: List<Entry> = listOf(
        Entry("🐶", "Dog", "Собака"), Entry("🐱", "Cat", "Кошка"), Entry("🦁", "Lion", "Лев"), Entry("🐎", "Horse", "Лошадь"),
        Entry("🦄", "Unicorn", "Единорог"), Entry("🐷", "Pig", "Свинья"), Entry("🐘", "Elephant", "Слон"), Entry("🐰", "Rabbit", "Кролик"),
        Entry("🐼", "Panda", "Панда"), Entry("🐓", "Rooster", "Петух"), Entry("🐧", "Penguin", "Пингвин"), Entry("🐢", "Turtle", "Черепаха"),
        Entry("🐟", "Fish", "Рыба"), Entry("🐙", "Octopus", "Осьминог"), Entry("🦋", "Butterfly", "Бабочка"), Entry("🌷", "Flower", "Цветок"),
        Entry("🌳", "Tree", "Дерево"), Entry("🌵", "Cactus", "Кактус"), Entry("🍄", "Mushroom", "Гриб"), Entry("🌏", "Globe", "Глобус"),
        Entry("🌙", "Moon", "Луна"), Entry("☁️", "Cloud", "Облако"), Entry("🔥", "Fire", "Огонь"), Entry("🍌", "Banana", "Банан"),
        Entry("🍎", "Apple", "Яблоко"), Entry("🍓", "Strawberry", "Клубника"), Entry("🌽", "Corn", "Кукуруза"), Entry("🍕", "Pizza", "Пицца"),
        Entry("🎂", "Cake", "Торт"), Entry("❤️", "Heart", "Сердце"), Entry("😀", "Smiley", "Улыбка"), Entry("🤖", "Robot", "Робот"),
        Entry("🎩", "Hat", "Шляпа"), Entry("👓", "Glasses", "Очки"), Entry("🔧", "Spanner", "Ключ"), Entry("🎅", "Santa", "Санта"),
        Entry("👍", "Thumbs Up", "Большой палец вверх"), Entry("☂️", "Umbrella", "Зонт"), Entry("⌛", "Hourglass", "Песочные часы"), Entry("⏰", "Clock", "Часы"),
        Entry("🎁", "Gift", "Подарок"), Entry("💡", "Light Bulb", "Лампочка"), Entry("📕", "Book", "Книга"), Entry("✏️", "Pencil", "Карандаш"),
        Entry("📎", "Paperclip", "Скрепка"), Entry("✂️", "Scissors", "Ножницы"), Entry("🔒", "Lock", "Замок"), Entry("🔑", "Key", "Ключ"),
        Entry("🔨", "Hammer", "Молоток"), Entry("☎️", "Telephone", "Телефон"), Entry("🏁", "Flag", "Флаг"), Entry("🚂", "Train", "Поезд"),
        Entry("🚲", "Bicycle", "Велосипед"), Entry("✈️", "Aeroplane", "Самолет"), Entry("🚀", "Rocket", "Ракета"), Entry("🏆", "Trophy", "Кубок"),
        Entry("⚽", "Ball", "Мяч"), Entry("🎸", "Guitar", "Гитара"), Entry("🎺", "Trumpet", "Труба"), Entry("🔔", "Bell", "Колокол"),
        Entry("⚓", "Anchor", "Якорь"), Entry("🎧", "Headphones", "Наушники"), Entry("📁", "Folder", "Папка"), Entry("📌", "Pin", "Булавка"),
    )
}

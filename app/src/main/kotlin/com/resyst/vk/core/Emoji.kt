package com.resyst.vk.core

/**
 * The emoji panel's data (r8): curated categories (Unicode ≤ 11, no skin-tone modifiers, so
 * they render on API 26 fonts; the view still drops any glyph the font lacks) and the recents
 * list. Pure JVM: storage is a plain string the service persists on-device.
 */
object Emoji {
    data class Category(val id: String, val label: String, val icon: String, val items: List<String>)

    private fun list(s: String) = s.trim().split(Regex("\\s+"))

    val CATEGORIES: List<Category> = listOf(
        Category("caras", "Caras", "😀", list("""
            😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔
            🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓
            🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 😤 😡 😠 🤬 😈 👿 💀
            💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊
        """)),
        Category("gestos", "Gestos y personas", "👍", list("""
            👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 ✍️ 💅 🤳 👀 👁️
            👅 👄 💋 🧠 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙋 🙆 🙅 🤷 🤦 🙇 💁 🙎 🙍 💃 🕺 👫 👭 👬 💏 💑
            👪 🤰 🧑‍💻 👩‍💻 👨‍💻 🧑‍🍳 🧑‍🎓 🧑‍🏫 🧑‍⚕️ 👮 🕵️ 💂 👷 🤴 👸 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🎅 🤶
        """)),
        Category("amor", "Corazones", "❤️", list("""
            ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💌 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭
            💤 🔥 ✨ 🌟 ⭐ 🎉 🎊
        """)),
        Category("naturaleza", "Animales y naturaleza", "🐶", list("""
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋
            🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦒 🐪 🐄 🐎 🐖 🐑 🐐
            🐕 🐈 🐓 🦃 🦜 🦢 🕊️ 🐇 🐁 🐿️ 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺
            🌸 🌼 🌻 🌞 🌝 🌚 🌙 🌎 🪐 ⭐ ⚡ ☄️ 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌬️ 🌊 💧 ☔
        """)),
        Category("comida", "Comida y bebida", "🍕", list("""
            🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐
            🍞 🥖 🥨 🧀 🥚 🍳 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙
            🍚 🍘 🍥 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧉 🥤 🍶 🍺 🍻
            🥂 🍷 🥃 🍸 🍹 🍾 🧊 🥄 🍴 🍽️
        """)),
        Category("actividades", "Actividades", "⚽", list("""
            ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 ⛸️ 🥌 🎿 ⛷️ 🏂 🏋️
            🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚴 🚵 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼
            🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🕹️ 🎰 🧩
        """)),
        Category("viajes", "Viajes y lugares", "🚗", list("""
            🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄
            🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿
            🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥
            🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🕋 ⛩️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
        """)),
        Category("objetos", "Objetos", "💡", list("""
            ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏱️ ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️
            🧯 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 🔫 💣 🧨 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🔮
            📿 🧿 💈 ⚗️ 🔭 🔬 💊 💉 🧬 🦠 🧪 🌡️ 🧹 🧺 🧻 🚽 🚿 🛁 🧼 🧽 🧴 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒
            🎁 🎈 🎏 🎀 🎊 🎉 🎎 🏮 🎐 ✉️ 📩 📨 📧 📦 🏷️ 📪 📬 📮 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅
            📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️
            🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
        """)),
        Category("simbolos", "Símbolos", "✅", list("""
            ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚫 ⛔ 📛 🔞 📵 🚭 ♻️ 🔱 ⚜️ 🔰 ⭕ 🆗
            🆕 🆓 🆒 🆙 🆘 🔝 🔜 🔙 🔚 🔛 ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ 🔀 🔁 🔂 ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️
            ↘️ ↙️ ↖️ ↕️ ↔️ 🔄 ↪️ ↩️ ⤴️ ⤵️ #️⃣ *️⃣ 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 🔤 🎵 🎶 💲 💱 ©️
            ®️ ™️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ ⬛ ⬜ 🏁 🚩
            🎌 🏴 🏳️ 🏳️‍🌈 🇦🇷 🇧🇴 🇧🇷 🇨🇱 🇨🇴 🇨🇷 🇨🇺 🇩🇴 🇪🇨 🇸🇻 🇬🇹 🇭🇳 🇲🇽 🇳🇮 🇵🇦 🇵🇾 🇵🇪 🇵🇷 🇺🇾 🇻🇪 🇪🇸 🇺🇸 🇬🇧 🇫🇷 🇩🇪 🇮🇹
            🇵🇹 🇯🇵 🇨🇦 🇪🇺
        """)),
    )

    /** Every curated emoji, once. */
    val ALL: List<String> = CATEGORIES.flatMap { it.items }.distinct()
}

/**
 * Most-recent-first list of emoji the user picked (on-device only, never written from an
 * incognito field). [MAX] entries; a re-pick moves the emoji to the front.
 */
class EmojiRecents(initial: List<String> = emptyList()) {
    private val items = ArrayList<String>()

    init { initial.forEach { if (valid(it) && it !in items && items.size < MAX) items += it } }

    fun items(): List<String> = items.toList()

    /** True when the list changed. */
    fun push(e: String): Boolean {
        if (!valid(e)) return false
        if (items.firstOrNull() == e) return false
        items.remove(e)
        items.add(0, e)
        while (items.size > MAX) items.removeAt(items.size - 1)
        return true
    }

    /** r10 ("Lo que sé de ti", V12): drops one recent. True when it was there. */
    fun remove(e: String): Boolean = items.remove(e)

    fun encode(): String = items.joinToString(SEP)

    companion object {
        const val MAX = 32
        private const val SEP = "\u001F"
        /** An emoji is short and has no letters/digits/whitespace of its own (keycaps excepted). */
        fun valid(e: String): Boolean =
            e.isNotEmpty() && e.length <= 16 && e.none { it.isWhitespace() || it == '\u001F' } &&
                (e.none { it.isLetter() } || e.any { Character.isSurrogate(it) })

        fun decode(s: String?): EmojiRecents = EmojiRecents(s?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList())
    }
}

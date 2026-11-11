package com.akkhara.keyboard

/**
 * Emoji for the keyboard's emoji panel: a small, common set per category (no download).
 * [KEYWORDS]: a few Sing Khmer / English words → emoji, used by the panel's search box
 * (typing "sok" finds 😊, "srolanh" finds ❤️).
 */
object Emoji {
    val CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "😀" to split("😀 😃 😄 😁 😆 😅 😂 🤣 🥲 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 💀 👻 👽 🤖 💩"),
        "👍" to split("👍 👎 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 👏 🙌 👐 🤲 🙏 ✍️ 💪 🦾 👀 👁️ 👅 👄 💋 🧠 🫶 👶 👧 🧒 👦 👩 🧑 👨 👵 🧓 👴 👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🎓 👩‍🏫 👨‍🍳 🙋 🙆 🙅 🤷 🤦 💁 🙇 🧘 🏃 💃 🕺 👫 👬 👭 💏 💑 👪"),
        "❤️" to split("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💯 💢 💥 💫 💦 💨 🕳️ 💬 🗨️ 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊 🎈 🎁 🏆 🥇 🥈 🥉"),
        "🐶" to split("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🐘 🦛 🦏 🐪 🦒 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐈 🐓 🦃 🕊️ 🐇 🐁 🌵 🎄 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌚 🌙 ⭐ ☀️ 🌤️ ⛅ 🌧️ ⛈️ 🌈 ❄️ ☃️ 🌊"),
        "🍜" to split("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🍞 🥖 🧀 🥚 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥛 🍼 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹"),
        "⚽" to split("⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🏹 🎣 🛹 ⛸️ 🎿 🏋️ 🤸 ⛹️ 🤺 🏌️ 🏇 🧘 🏄 🏊 🚴 🎮 🕹️ 🎲 ♟️ 🎯 🎳 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎬 🎨"),
        "🚗" to split("🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚔 🚍 🚘 🚖 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛥️ 🚢 ⚓ 🗺️ 🗿 🗽 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🏕️ 🏠 🏡 🏢 🏬 🏣 🏥 🏦 🏨 🏪 🏫 🏛️ ⛪ 🕌 🛕 🌅 🌄 🌃 🏙️ 🌉"),
        "💡" to split("⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 🔧 🔨 🛠️ 🔩 ⚙️ 🔫 💣 🔪 🛡️ 🔮 💊 💉 🌡️ 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎁 ✉️ 📦 📝 📁 📅 📌 📎 ✂️ 🖊️ ✏️ 🔍 🔒 🔓"),
        "✅" to split("✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚫 ⛔ 📵 🔞 ☢️ ♻️ 🔰 ⭕ 🛑 💲 ©️ ®️ ™️ 🔟 🔢 #️⃣ *️⃣ ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔀 🔁 🔂 ◀️ 🔼 🔽 ⬆️ ⬇️ ⬅️ ➡️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ 🔄 🎵 🎶 💲 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🇰🇭 🇰🇷 🇺🇸 🇹🇭 🇻🇳 🇯🇵 🇨🇳"),
    )

    /** Sing Khmer + English keywords → emoji (the search box uses the same typing). */
    val KEYWORDS: Map<String, List<String>> = mapOf(
        "sok" to split("😊 🙂 😄"), "sabay" to split("😄 😁 🥳"), "sbay" to split("😄 😁 🥳"), "happy" to split("😄 😁 🥳"),
        "srolanh" to split("❤️ 🥰 😍"), "sl" to split("❤️ 🥰 😍"), "love" to split("❤️ 🥰 😍"), "nek" to split("🥺 💭 ❤️"),
        "seuch" to split("😂 🤣 😆"), "haha" to split("😂 🤣 😆"), "lol" to split("😂 🤣"),
        "yum" to split("😭 😢 🥺"), "cry" to split("😭 😢"), "khoeng" to split("😡 😠 🤬"), "angry" to split("😡 😠"),
        "nham" to split("🍜 🍚 😋"), "bay" to split("🍚 🍛"), "food" to split("🍜 🍚 🍕"), "kafe" to split("☕"), "coffee" to split("☕"),
        "orkun" to split("🙏 😊"), "rkun" to split("🙏"), "thanks" to split("🙏"), "som" to split("🙏"),
        "ok" to split("👌 👍"), "baat" to split("👍 👌"), "jas" to split("👍"), "yes" to split("👍"),
        "ot" to split("👎 🙅"), "no" to split("🙅 👎"), "keng" to split("😴 💤"), "sleep" to split("😴 💤"),
        "loy" to split("😎 🔥"), "cool" to split("😎 🔥"), "sart" to split("😍 ✨ 🌸"), "pretty" to split("😍 ✨"),
        "lg" to split("🎮 ⚽"), "game" to split("🎮 🕹️"), "rean" to split("📚 ✏️ 🏫"), "school" to split("🏫 📚"),
        "luy" to split("💰 💸 💵"), "money" to split("💰 💸"), "khmer" to split("🇰🇭"), "srok" to split("🇰🇭"),
        "fire" to split("🔥"), "party" to split("🥳 🎉"), "birthday" to split("🎂 🎉 🎁"), "rain" to split("🌧️ ☔"),
    )

    private fun split(s: String) = s.split(" ").filter { it.isNotEmpty() }
}

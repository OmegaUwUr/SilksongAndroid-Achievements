package dev.silksong.launcher

import `in`.dragonbra.javasteam.types.KeyValue

/** Public artwork paths only. Metadata cannot supply a host, query string or traversal path. */
object SteamArtPaths {
    fun metadata(info: KeyValue): Map<String, List<String>> {
        val common = info["common"]
        fun leaves(node: KeyValue): List<String> = listOfNotNull(node.asString()) +
            node.children.sortedBy { if (it.name in listOf("english", "en")) 0 else 1 }.flatMap(::leaves)
        return mapOf("portrait" to "library_capsule", "hero" to "library_hero", "logo" to "library_logo")
            .mapValues { (kind, key) ->
                val filename = when (kind) { "portrait" -> "library_600x900.jpg"; "hero" -> "library_hero.jpg"; else -> "logo.png" }
                listOf("library_assets", "library_assets_full").flatMap { leaves(common[it][key]) }
                    .mapNotNull { value -> when {
                        value.matches(Regex("[a-fA-F0-9]{40}")) -> "$value/$filename"
                        safe(value) -> value
                        else -> null
                    } }.distinct().take(8)
            }
    }

    fun safe(path: String): Boolean = path.length in 5..240 && !path.startsWith('/') &&
        !path.contains("..") && path.matches(Regex("[A-Za-z0-9_/-]+\\.(?:jpg|png|jpeg)"))
}

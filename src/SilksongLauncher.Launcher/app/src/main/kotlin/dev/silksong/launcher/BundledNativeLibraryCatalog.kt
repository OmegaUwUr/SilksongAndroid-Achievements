package dev.silksong.launcher

/** Reviewed C-library families only. Never resolve Windows, store, engine or middleware names by analogy. */
object BundledNativeLibraryCatalog {
    val dependencies: Map<String, Set<String>> = mapOf(
        "libsqlite3.so" to emptySet(), "liblz4.so" to emptySet(), "libzstd.so" to emptySet(),
        "libogg.so" to emptySet(), "libvorbis.so" to setOf("libogg.so"),
        "libvorbisfile.so" to setOf("libvorbis.so", "libogg.so"),
        "libvorbisenc.so" to setOf("libvorbis.so", "libogg.so"), "libpng16.so" to emptySet())

    fun candidates(declared: Collection<String>): Set<String> = dependencies.keys.filterTo(sortedSetOf()) { filename ->
        val base = filename.removePrefix("lib").removeSuffix(".so")
        declared.any { it == base || it == "lib$base" || it == filename }
    }

    fun closure(roots: Set<String>): Set<String> {
        require(roots.all { it in dependencies }) { "Unknown included library." }
        val result = roots.toMutableSet()
        val queue = java.util.ArrayDeque(roots)
        while (queue.isNotEmpty()) for (dependency in dependencies.getValue(queue.removeFirst()))
            if (result.add(dependency)) queue.add(dependency)
        return result.toSortedSet()
    }

    fun loadOrder(names: Set<String>): List<String> {
        require(names.all { it in dependencies })
        val result = mutableListOf<String>(); val seen = mutableSetOf<String>()
        fun visit(name: String) {
            if (!seen.add(name)) return
            for (dependency in dependencies.getValue(name).sorted()) { require(dependency in names); visit(dependency) }
            result += name
        }
        names.sorted().forEach(::visit)
        return result
    }

    /** Add missing roots; preserve user libraries and refuse an untested mixture of dependent builds. */
    fun additions(declared: Collection<String>, existing: Map<String, String>, bundled: Map<String, String>): Set<String> {
        require(bundled.keys == dependencies.keys) { "The included library bundle is incomplete." }
        val roots = candidates(declared) - existing.keys
        val required = closure(roots)
        for (name in required.intersect(existing.keys)) require(existing[name] == bundled[name]) {
            "The included libraries require a different build of $name than your imported pack. Import a complete compatible pack; existing libraries were preserved."
        }
        return required - existing.keys
    }
}

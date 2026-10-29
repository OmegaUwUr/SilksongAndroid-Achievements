package dev.silksong.launcher

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Read-only inventory. It never executes game code, retargets assets or changes the active depot. */
object GameCompatibility {
    data class Header(val version: String, val platform: Int, val format: Int)
    data class Report(val title: String, val facts: List<String>, val blockers: List<String>, val notes: List<String>) {
        val basicChecksPassed get() = blockers.isEmpty()
        fun asText(): String = buildString {
            append("Game compatibility report v1\n").append(title).append('\n')
            append(if (basicChecksPassed) "Basic checks passed; gameplay is not verified.\n" else "Port unavailable for these files.\n")
            if (blockers.isNotEmpty()) {
                append("\nNeeds attention\n"); blockers.forEach { append("- ").append(it).append('\n') }
            }
            append("\nObserved files\n"); facts.forEach { append("- ").append(it).append('\n') }
            append("\nNot verified\n"); notes.forEach { append("- ").append(it).append('\n') }
        }
    }

    private fun normalized(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun textFile(file: File): String? = if (file.isFile && file.length() in 1L..8192L) {
        runCatching { file.readText().trimStart('\uFEFF').trim() }.getOrNull()
    } else null

    private fun product(data: File): String? = textFile(File(data, "app.info"))?.lineSequence()
        ?.drop(1)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun identityMatches(data: File, products: List<String>, names: List<String>): Boolean {
        // An explicit product name wins over a directory renamed by the user.
        val declared = product(data)
        return if (declared != null) products.any { normalized(it) == normalized(declared) }
        else names.any { it.equals(data.name, ignoreCase = true) }
    }

    fun silksongIdentityProblem(data: File): String? = if (identityMatches(data,
        listOf("Hollow Knight Silksong", "Hollow Knight: Silksong", "Silksong"),
        listOf("Hollow Knight Silksong_Data"))) null else
        "These files could not be identified as Hollow Knight: Silksong. " +
            "Choose the original Silksong data folder, or use Games & compatibility to inspect another game."

    fun requireSilksongData(data: File) {
        silksongIdentityProblem(data)?.let { throw IOException(it) }
    }

    /** Look for a PE CLI directory, not just a file named Assembly-CSharp.dll. */
    fun hasManagedHeader(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 64 || input.readUnsignedShort() != 0x4d5a) return@use false
            input.seek(60)
            val pe = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            if (pe !in 64L..1048576L || pe+24 > input.length()) return@use false
            input.seek(pe)
            if (input.readInt() != 0x50450000) return@use false
            input.seek(pe+20)
            val optionalSize = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            val optional = pe+24
            if (optional+optionalSize > input.length()) return@use false
            input.seek(optional)
            val magic = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            val directories = when (magic) { 0x10b -> 96; 0x20b -> 112; else -> return@use false }
            if (optionalSize < directories+15*8) return@use false
            input.seek(optional+directories-4)
            if (Integer.reverseBytes(input.readInt()) < 15) return@use false
            input.seek(optional+directories+14*8)
            val rva = Integer.reverseBytes(input.readInt())
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            rva != 0 && size >= 72
        }
    }.getOrDefault(false)

    /** Assembly-definition projects can use any script assembly names. This is a bounded
     * source inventory only; Cecil, registration, reference and Android-profile audits
     * still have to validate the complete set before compilation. */
    fun monoAssemblies(data: File): List<File> {
        val managed = File(data, "Managed")
        require(data.canonicalFile == data.absoluteFile && managed.canonicalFile == managed.absoluteFile) {
            "Managed source must not traverse symbolic links."
        }
        val files = managed.listFiles()?.filter { it.extension.equals("dll", true) }?.sortedBy { it.name }
            ?: throw IOException("A readable Managed assembly directory is required.")
        require(files.size in 1..1024) { "A complete Mono source needs 1–1024 managed assemblies." }
        require(files.map { it.name.lowercase(Locale.ROOT) }.distinct().size == files.size) {
            "Case-conflicting managed assembly names need an adapter."
        }
        for (file in files) {
            require(file.name.none { it == '\\' || it == ':' || it.code < 32 } && file.extension == "dll") { "Unsupported managed assembly filename." }
            require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..(128L * 1024 * 1024) && hasManagedHeader(file)) {
                "Invalid, linked or incomplete managed assembly: ${file.name}"
            }
        }
        val registration = File(data, "ScriptingAssemblies.json")
        if (registration.exists()) {
            require(registration.isFile && registration.canonicalFile == registration.absoluteFile && registration.length() in 1..1048576) {
                "Invalid Mono assembly registration metadata."
            }
            val json = org.json.JSONObject(registration.readText())
            val names = json.getJSONArray("names")
            require(names.length() <= 1024 && json.getJSONArray("types").length() == names.length()) { "Incomplete Mono assembly registration metadata." }
            val seen = mutableSetOf<String>()
            val present = files.map { it.name }.toSet()
            for (index in 0 until names.length()) {
                val name = names.getString(index)
                require(seen.add(name.lowercase(Locale.ROOT)) && name in present) { "Registered Mono assembly is missing or duplicated: $name" }
            }
        }
        return files
    }

    fun hasMonoAssemblies(data: File): Boolean = runCatching { monoAssemblies(data).isNotEmpty() }.getOrDefault(false)

    private fun binaryKind(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 20) return@use "unknown/truncated"
            val bytes = ByteArray(20); input.readFully(bytes)
            if (bytes[0] != 0x7f.toByte() || String(bytes, 1, 3, Charsets.US_ASCII) != "ELF") return@use "non-ELF; review required"
            val order = when (bytes[5].toInt()) { 1 -> ByteOrder.LITTLE_ENDIAN; 2 -> ByteOrder.BIG_ENDIAN; else -> return@use "unknown ELF byte order" }
            val machine = ByteBuffer.wrap(bytes).order(order).getShort(18).toInt() and 0xffff
            "ELF " + when (machine) { 3 -> "x86"; 62 -> "x86-64"; 40 -> "ARM"; 183 -> "AArch64 (Android ABI unverified)"; else -> "machine $machine" }
        }
    }.getOrDefault("unreadable")

    /** Serialized-file header only: a bounded read, with Unity's metadata byte order. */
    fun readHeader(file: File): Header? = runCatching {
        val bytes = ByteArray(256)
        val count = file.inputStream().use { input ->
            var total = 0
            while (total < bytes.size) {
                val n = input.read(bytes, total, bytes.size-total)
                if (n <= 0) break
                total += n
            }
            total
        }
        readHeader(bytes.copyOf(count), file.length())
    }.getOrNull()

    internal fun readHeader(bytes: ByteArray, length: Long): Header? = runCatching {
        val count = bytes.size
        if (count < 25) return@runCatching null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val format = buffer.getInt(8)
        if (format !in 9..22) return@runCatching null
        val start = if (format >= 22) 48 else 20
        if (start >= count) return@runCatching null
        val declaredLength = if (format >= 22) buffer.getLong(24) else (buffer.getInt(4).toLong() and 0xffffffffL)
        if (declaredLength != length) return@runCatching null
        val end = (start until minOf(count, start+128)).firstOrNull { bytes[it] == 0.toByte() }
            ?: return@runCatching null
        if (end+5 > count || bytes[16].toInt() !in 0..1) return@runCatching null
        val version = String(bytes, start, end-start, Charsets.US_ASCII)
        if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[abfp][0-9]+(?:[-_][A-Za-z0-9._-]+)?"))) return@runCatching null
        buffer.order(if (bytes[16] == 0.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        Header(version, buffer.getInt(end+1), format)
    }.getOrNull()

    /** Refuse ambiguous selections rather than taking the first game in a library folder. */
    fun findDataFolders(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val base = root.canonicalFile.toPath()
        var level = listOf(root)
        var visited = 0
        val hits = mutableListOf<File>()
        repeat(5) {
            val next = mutableListOf<File>()
            for (dir in level) {
                if (++visited > 128) throw IOException("Too many folders. Choose one game's installation folder.")
                if (!dir.canonicalFile.toPath().startsWith(base)) continue
                if (File(dir, "globalgamemanagers").isFile) { hits += dir; continue }
                val children = dir.listFiles() ?: throw IOException("The selected folder cannot be read.")
                if (children.size > 256) throw IOException("Too many entries. Choose one game's data folder.")
                for (child in children.sortedBy { it.name }) {
                    val macResources = dir.name == "Contents" && child.name == "Resources"
                    if (child.isDirectory && (macResources || child.name !in listOf("Managed", "StreamingAssets", "Plugins", "Resources"))) next += child
                }
            }
            level = next
        }
        return hits.distinctBy { it.canonicalPath }
    }

    internal fun platformName(id: Int) = when (id) {
        24 -> "Linux"; 5, 19 -> "Windows"; 2 -> "macOS"; 13 -> "Android"; else -> "Unknown ($id)"
    }

    fun inspect(root: File, profile: GameProfile): Report {
        val facts = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        val notes = mutableListOf("Shader variants, textures, serialization, native plugin API/ABI, input, saves and gameplay need game-specific testing.",
            "A Unity version match is not proof that a game can be ported.")
        facts += "Selected game: ${profile.title}"
        facts += "Steam App ID: ${profile.steamAppId ?: "not configured"}"
        if (!profile.hasPortAdapter) notes += "This game can use Download & prepare on its library page. The common builder inspects actual files and components before allowing compilation."
        if (profile.id == "hollow-knight" && profile.hasPortAdapter)
            notes += "Experimental adapter: exact Linux 1.5.12620 file hashes are checked during setup. Shared Steam sync, controller and generic touch integration are available."
        val candidates = findDataFolders(root)
        if (candidates.size != 1) {
            blockers += if (candidates.isEmpty()) "No readable Unity data folder was found. Select the installed game's folder containing globalgamemanagers."
                else "Multiple Unity data folders were found. Choose one installation or its data folder."
            return Report(profile.title, facts, blockers, notes)
        }
        val data = candidates.single()
        facts += "Data directory: ${data.name}"
        facts += "Declared product: ${product(data) ?: "not provided"}"
        if (profile.products.isNotEmpty() && !identityMatches(data, profile.products, profile.dataDirectories))
            blockers += "The files do not identify themselves as ${profile.title}. Select the matching game."
        val header = readHeader(File(data, "globalgamemanagers"))
        facts += "Unity version: ${header?.version ?: "unknown or unreadable header"}"
        facts += "Serialized platform: ${header?.let { platformName(it.platform) } ?: "unknown"}"
        if (header == null) blockers += "Cannot verify the Unity version/platform. Files may be incomplete or use an unsupported serialized format."
        else {
            if (profile.hasPortAdapter && header.platform != 24) blockers += "This dedicated adapter requires Linux game files."
            else if (!profile.hasPortAdapter && header.platform !in PortTarget.desktopPlatforms)
                blockers += "This source platform needs a converter provider."
            if (profile.unityVersions.isEmpty() && !UnityDistribution.supports(header.version)) blockers += "No verified Android engine/toolchain is registered for Unity ${header.version}."
            else if (profile.unityVersions.isEmpty()) notes += if (UnityDistribution.isPinned(header.version))
                "A pinned Android engine provider is available for this version; run Download & prepare for the full dependency and shader checks."
                else "This stable Unity 6.0 patch is eligible for automatic exact-release resolution. Run Download & prepare to verify the runtime, dependencies and shaders; metadata discovery alone does not enable compilation."
            else if (header.version !in profile.unityVersions) blockers += "This Unity version does not match this game's registered engine/toolchain."
        }
        val managed = File(data, "Managed")
        val mono = if (profile.hasPortAdapter) hasManagedHeader(File(managed, "Assembly-CSharp.dll")) else hasMonoAssemblies(data)
        val il2cpp = File(data, "il2cpp_data/Metadata/global-metadata.dat").isFile ||
            File(managed, "Metadata/global-metadata.dat").isFile || File(data.parentFile, "GameAssembly.dll").isFile ||
            File(data.parentFile, "GameAssembly.so").isFile || File(data.parentFile, "libil2cpp.so").isFile
        facts += "Scripting: ${if (mono && il2cpp) "mixed/ambiguous" else if (mono) "managed game assembly present" else if (il2cpp) "precompiled IL2CPP" else "unknown"}"
        if (!mono || il2cpp) blockers += "A complete managed (Mono) build is required. Precompiled desktop IL2CPP cannot be converted by this pipeline."
        val assemblyNames = managed.listFiles().orEmpty().filter { it.isFile && it.extension.equals("dll", true) }.map { it.name }.sorted()
        facts += "Managed assemblies (${assemblyNames.size}): ${assemblyNames.take(256).joinToString(", ").ifEmpty { "none" }}"
        val aa = File(data, "StreamingAssets/aa")
        val catalog = File(aa, "catalog.bin").isFile && File(aa, "settings.json").isFile
        val levels = data.listFiles().orEmpty().count { it.isFile && it.name.matches(Regex("level[0-9]+")) }
        facts += "Content: ${if (catalog) "binary Addressables catalog" else "no binary Addressables catalog"}; $levels loose level files"
        if (profile.id == "silksong" && profile.hasPortAdapter && !catalog) blockers += "The Silksong adapter requires StreamingAssets/aa/catalog.bin and settings.json."
        if (!profile.hasPortAdapter) notes += "Basic inventory does not enable compilation. Run full preparation to verify common asset conversion, managed/native APIs and input requirements. Generic Steam/Cloud integration is not enabled."

        val plugins = mutableListOf<String>()
        var seen = 0
        val dataPath = data.canonicalFile.toPath()
        val entered = mutableSetOf<String>()
        val pluginTree = File(data, "Plugins").walkTopDown().onEnter { directory ->
            val canonical = directory.canonicalFile
            canonical.toPath().startsWith(dataPath) && entered.add(canonical.path)
        }.maxDepth(6)
        for (file in pluginTree) {
            if (++seen > 2048) { notes += "Native plugin inventory was truncated at 2048 entries."; break }
            if (!file.canonicalFile.toPath().startsWith(dataPath)) continue
            if (file.isFile && file.extension.lowercase(Locale.ROOT) in listOf("so", "dll", "dylib", "bundle")) {
                plugins += file.relativeTo(data).invariantSeparatorsPath + " [" + binaryKind(file) + "]"
            }
        }
        facts += "Native plugin candidates (${plugins.size}): ${plugins.take(128).sorted().joinToString(", ").ifEmpty { "none in the scanned Plugins tree" }}"
        notes += "Plugin filenames are an inventory, not proof of Android compatibility; libraries outside Plugins and managed P/Invokes require review."
        notes += PortDependencyAudit.requirements(assemblyNames.map { "Managed/$it" } + plugins.map { it.substringBefore(" [") })
        if (plugins.size > 128) notes += "Only the first 128 plugin names are included."
        return Report(profile.title, facts, blockers.distinct(), notes)
    }
}

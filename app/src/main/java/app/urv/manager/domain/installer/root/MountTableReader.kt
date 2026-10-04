package app.urv.manager.domain.installer.root

data class MountInfoEntry(
    val mountId: Int,
    val parentId: Int,
    val root: String,
    val mountPoint: String,
    val options: Set<String>,
    val fileSystem: String,
    val source: String,
    val superOptions: Set<String>
)

class MountTableReader(private val shell: RootShellGateway? = null) {
    suspend fun read(): List<MountInfoEntry> {
        val gateway = requireNotNull(shell) { "A shell gateway is required to read mountinfo" }
        val result = gateway.runIsolatedBounded(
            "cat /proc/self/mountinfo",
            READ_TIMEOUT_SECONDS,
            "root mount table read"
        ).requireSuccess("Read mount table")
        return parse(result.stdout)
    }

    suspend fun mountsAt(paths: Set<String>): List<MountInfoEntry> =
        read().filter { it.mountPoint in paths }

    fun parse(lines: Iterable<String>): List<MountInfoEntry> = lines.mapNotNull(::parseLine)

    fun parseLine(line: String): MountInfoEntry? {
        val fields = line.trim().split(' ')
        val separator = fields.indexOf("-")
        if (separator < 6 || fields.size <= separator + 3) return null
        return runCatching {
            MountInfoEntry(
                mountId = fields[0].toInt(),
                parentId = fields[1].toInt(),
                root = unescape(fields[3]),
                mountPoint = unescape(fields[4]),
                options = fields[5].split(',').filter(String::isNotBlank).toSet(),
                fileSystem = fields[separator + 1],
                source = unescape(fields[separator + 2]),
                superOptions = fields[separator + 3].split(',').filter(String::isNotBlank).toSet()
            )
        }.getOrNull()
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 15L

        fun unescape(value: String): String = value.replace(Regex("\\\\([0-7]{3})")) { match ->
            match.groupValues[1].toInt(8).toChar().toString()
        }
    }
}

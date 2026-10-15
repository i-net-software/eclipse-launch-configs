package de.inetsoftware.eclipselaunch.model

/** Attribute keys of the Eclipse JDT launch configurations. */
object Keys {
    const val PROJECT = "org.eclipse.jdt.launching.PROJECT_ATTR"
    const val MAIN_TYPE = "org.eclipse.jdt.launching.MAIN_TYPE"
    const val VM_ARGUMENTS = "org.eclipse.jdt.launching.VM_ARGUMENTS"
    const val PROGRAM_ARGUMENTS = "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS"
    const val WORKING_DIRECTORY = "org.eclipse.jdt.launching.WORKING_DIRECTORY"
    const val JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER"
    const val CLASSPATH = "org.eclipse.jdt.launching.CLASSPATH"
    const val DEFAULT_CLASSPATH = "org.eclipse.jdt.launching.DEFAULT_CLASSPATH"
    const val ENVIRONMENT = "org.eclipse.debug.core.environmentVariables"
    const val APPEND_ENVIRONMENT = "org.eclipse.debug.core.appendEnvironmentVariables"
    const val MAPPED_RESOURCE_PATHS = "org.eclipse.debug.core.MAPPED_RESOURCE_PATHS"
    const val MAPPED_RESOURCE_TYPES = "org.eclipse.debug.core.MAPPED_RESOURCE_TYPES"
    const val JUNIT_CONTAINER = "org.eclipse.jdt.junit.CONTAINER"
    const val JUNIT_TESTNAME = "org.eclipse.jdt.junit.TESTNAME"
    const val JUNIT_TEST_KIND = "org.eclipse.jdt.junit.TEST_KIND"
    const val EXCLUDE_TEST_CODE = "org.eclipse.jdt.launching.ATTR_EXCLUDE_TEST_CODE"

    const val JRE_CONTAINER_PREFIX = "org.eclipse.jdt.launching.JRE_CONTAINER"
    const val STANDARD_VM_TYPE = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType"
}

/** The launch configuration types this plugin understands. */
enum class LaunchKind(val typeId: String) {
    JUNIT("org.eclipse.jdt.junit.launchconfig"),
    APPLICATION("org.eclipse.jdt.launching.localJavaApplication");

    companion object {
        fun of(typeId: String): LaunchKind? = entries.firstOrNull { it.typeId == typeId }
    }
}

/** What a JUnit launch runs. */
sealed interface TestTarget {
    data class Class(val className: String) : TestTarget
    data class Method(val className: String, val method: String) : TestTarget

    /**
     * All tests of a project, a source folder or a package, stored by Eclipse as a Java element handle.
     * [rootMemento] is the raw source folder part including its classpath attributes, kept for writing back.
     */
    data class Container(val project: String, val rootMemento: String?, val packageName: String?) : TestTarget {
        /** Source folder path relative to the project, e.g. `test` */
        val rootPath: String? get() = rootMemento?.let { JavaElementMemento.rootPath(it) }

        fun toMemento(): String = buildString {
            append('=').append(JavaElementMemento.escape(project))
            if (rootMemento != null) append('/').append(rootMemento)
            if (packageName != null) append('<').append(JavaElementMemento.escape(packageName))
        }
    }
}

/**
 * The part of a launch configuration that maps to IntelliJ IDEA settings. Values are raw Eclipse strings,
 * variables like `${workspace_loc}` are not resolved.
 */
data class LaunchSettings(
    val kind: LaunchKind,
    val project: String?,
    val mainType: String?,
    val testTarget: TestTarget?,
    val vmArguments: String,
    val programArguments: String,
    /** `null` means the Eclipse default: the project folder */
    val workingDirectory: String?,
    val environment: Map<String, String>,
    val appendEnvironment: Boolean,
    val jreContainer: String?,
    /** Eclipse runs applications with the test code and test dependencies of the project unless this is set */
    val excludeTestCode: Boolean = false,
) {
    companion object {
        fun read(file: LaunchFile): LaunchSettings {
            val kind = LaunchKind.of(file.type) ?: throw LaunchFileException("Unsupported launch type ${file.type}")
            val mainType = file.getString(Keys.MAIN_TYPE)?.takeIf { it.isNotBlank() }
            val testTarget = if (kind == LaunchKind.JUNIT) {
                val container = file.getString(Keys.JUNIT_CONTAINER)?.takeIf { it.isNotBlank() }
                val testName = file.getString(Keys.JUNIT_TESTNAME)?.takeIf { it.isNotBlank() }
                when {
                    container != null -> JavaElementMemento.parseContainer(container)
                    mainType != null && testName != null -> TestTarget.Method(mainType, testName)
                    mainType != null -> TestTarget.Class(mainType)
                    else -> null
                }
            } else null
            return LaunchSettings(
                kind = kind,
                project = file.getString(Keys.PROJECT)?.takeIf { it.isNotBlank() },
                mainType = if (kind == LaunchKind.APPLICATION) mainType else null,
                testTarget = testTarget,
                vmArguments = file.getString(Keys.VM_ARGUMENTS) ?: "",
                programArguments = file.getString(Keys.PROGRAM_ARGUMENTS) ?: "",
                workingDirectory = file.getString(Keys.WORKING_DIRECTORY)?.takeIf { it.isNotBlank() },
                environment = file.getMap(Keys.ENVIRONMENT) ?: emptyMap(),
                appendEnvironment = file.getBoolean(Keys.APPEND_ENVIRONMENT) ?: true,
                jreContainer = file.getString(Keys.JRE_CONTAINER)?.takeIf { it.isNotBlank() },
                excludeTestCode = file.getBoolean(Keys.EXCLUDE_TEST_CODE) ?: false,
            )
        }

        /** Writes the fields that differ between [old] and [new] into [file]; everything else stays untouched. */
        fun write(file: LaunchFile, old: LaunchSettings, new: LaunchSettings) {
            if (old.project != new.project) file.setString(Keys.PROJECT, new.project)
            if (old.vmArguments != new.vmArguments) file.setString(Keys.VM_ARGUMENTS, new.vmArguments.ifEmpty { null })
            if (old.programArguments != new.programArguments) file.setString(Keys.PROGRAM_ARGUMENTS, new.programArguments.ifEmpty { null })
            if (old.workingDirectory != new.workingDirectory) file.setString(Keys.WORKING_DIRECTORY, new.workingDirectory)
            if (old.environment != new.environment) file.setMap(Keys.ENVIRONMENT, new.environment.ifEmpty { null })
            if (old.appendEnvironment != new.appendEnvironment) {
                // Eclipse's default is "append", which needs no attribute
                file.setBoolean(Keys.APPEND_ENVIRONMENT, if (new.appendEnvironment) null else false)
            }
            if (old.jreContainer != new.jreContainer) file.setString(Keys.JRE_CONTAINER, new.jreContainer)
            if (new.kind == LaunchKind.APPLICATION) {
                if (old.mainType != new.mainType) file.setString(Keys.MAIN_TYPE, new.mainType)
            } else if (old.testTarget != new.testTarget) {
                when (val target = new.testTarget) {
                    is TestTarget.Class -> {
                        file.setString(Keys.JUNIT_CONTAINER, "")
                        file.setString(Keys.MAIN_TYPE, target.className)
                        if (file.has(Keys.JUNIT_TESTNAME)) file.setString(Keys.JUNIT_TESTNAME, "")
                    }
                    is TestTarget.Method -> {
                        file.setString(Keys.JUNIT_CONTAINER, "")
                        file.setString(Keys.MAIN_TYPE, target.className)
                        file.setString(Keys.JUNIT_TESTNAME, target.method)
                    }
                    is TestTarget.Container -> {
                        file.setString(Keys.JUNIT_CONTAINER, target.toMemento())
                        file.setString(Keys.MAIN_TYPE, "")
                        if (file.has(Keys.JUNIT_TESTNAME)) file.setString(Keys.JUNIT_TESTNAME, "")
                    }
                    null -> {}
                }
            }
        }

        /** The JRE container for a Java version, as Eclipse writes it for an execution environment. */
        fun jreContainerFor(javaVersion: Int): String {
            val env = if (javaVersion <= 8) "JavaSE-1.$javaVersion" else "JavaSE-$javaVersion"
            return "${Keys.JRE_CONTAINER_PREFIX}/${Keys.STANDARD_VM_TYPE}/$env"
        }

        /**
         * The JRE named by a container path: either an execution environment (`JavaSE-25` → version 25) or the
         * name of an installed JRE. Returns `null` for the workspace default.
         */
        fun parseJreContainer(container: String?): Jre? {
            if (container == null) return null
            val parts = container.removePrefix(Keys.JRE_CONTAINER_PREFIX).trim('/').split('/')
            if (parts.size < 2) return null
            val name = parts.drop(1).joinToString("/")
            val version = Regex("""(?:J2SE|JavaSE)-(?:1\.)?(\d+)""").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
            return Jre(name, version)
        }
    }

    data class Jre(val name: String, val executionEnvironmentVersion: Int?)
}

/** Reading and writing JDT Java element handle mementos, as used in `org.eclipse.jdt.junit.CONTAINER`. */
object JavaElementMemento {
    private const val ESCAPE = '\\'
    private const val SPECIAL = "\\=/<!([{^*~@'\"|;:?#%+}>]&`"

    fun escape(value: String): String = buildString {
        for (c in value) {
            if (c in SPECIAL) append(ESCAPE)
            append(c)
        }
    }

    /** Parses `=Project/root=/attr=/value=/<package` */
    fun parseContainer(memento: String): TestTarget.Container? {
        if (!memento.startsWith("=")) return null
        val project = StringBuilder()
        var i = 1
        while (i < memento.length && memento[i] != '/' && memento[i] != '<') {
            if (memento[i] == ESCAPE && i + 1 < memento.length) i++
            project.append(memento[i]); i++
        }
        var root: String? = null
        if (i < memento.length && memento[i] == '/') {
            val start = ++i
            while (i < memento.length && memento[i] != '<') {
                if (memento[i] == ESCAPE) i++
                i++
            }
            root = memento.substring(start, minOf(i, memento.length))
        }
        var pkg: String? = null
        if (i < memento.length && memento[i] == '<') {
            pkg = unescape(memento.substring(i + 1))
        }
        return TestTarget.Container(project.toString(), root, pkg)
    }

    /** The folder path of a package fragment root memento, without its classpath attributes. */
    fun rootPath(rootMemento: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rootMemento.length) {
            val c = rootMemento[i]
            if (c == ESCAPE && i + 1 < rootMemento.length) {
                sb.append(rootMemento[i + 1]); i += 2; continue
            }
            if (c == '=') break
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** A root memento for a folder without classpath attributes. */
    fun rootMemento(path: String): String = escape(path).replace("\\/", "/")

    private fun unescape(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == ESCAPE && i + 1 < value.length) i++
            sb.append(value[i]); i++
        }
        return sb.toString()
    }
}

package de.inetsoftware.eclipselaunch.run

import com.intellij.openapi.module.Module
import com.intellij.openapi.util.io.FileUtil
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.model.EclipseVariables
import de.inetsoftware.eclipselaunch.sync.EclipseWorkspace
import de.inetsoftware.eclipselaunch.sync.LaunchSyncService
import java.io.File

/** Resolves the Eclipse values of a configuration for a launch: variables, then output folders. */
class LaunchResolver(config: EclipseLinkedConfiguration) {
    private val project = config.moduleBased.project
    private val service = LaunchSyncService.getInstance(project)
    private val link = config.link
    private val entry = link?.path?.let { service.entry(it) }
    private val projectDir = entry?.context?.projectDir ?: link?.path?.let { EclipseWorkspace.findProjectDir(File(it).parentFile) }

    val variables: EclipseVariables = service.workspace.variables(entry?.settings?.project ?: entry?.context?.eclipseProject, projectDir)
    val rewriter: OutputRewriter? = if (link?.rewriteOutput != false) OutputRewriter(service.workspace) else null

    /** Needs a read action when output folders are rewritten. */
    fun resolve(text: String): String = variables.resolve(text).let { rewriter?.rewrite(it) ?: it }
}

/**
 * Replaces `<eclipse project>/bin/<source set>` with the output folders of the IDEA modules compiling those sources.
 * Remembers what it did for the launch report and the build before launch.
 */
class OutputRewriter(private val workspace: EclipseWorkspace) {
    private val cache = HashMap<String, List<Module>>()

    /** Eclipse output folder → IDEA output folders */
    val mapped = LinkedHashMap<String, List<String>>()

    /** Eclipse output folders of known projects without an IDEA module */
    val unmapped = LinkedHashSet<String>()

    /** Modules whose output is used */
    val modules = LinkedHashSet<Module>()

    fun rewrite(text: String): String = PATTERN.replace(text) { m ->
        val dir = m.groupValues[1]
        val set = m.groupValues[2]
        val file = File(dir)
        if (!file.isAbsolute || !workspace.isProjectDir(file)) return@replace m.value
        val owners = cache.getOrPut("$dir|$set") { workspace.outputModules(file, set) }
        val outputs = owners.mapNotNull { workspace.outputPath(it, set.contains("test", ignoreCase = true)) }.distinct()
        if (outputs.isEmpty()) {
            unmapped += m.value
            return@replace m.value
        }
        modules += owners
        mapped[m.value] = outputs
        // Several modules: keep the class path separator the value already uses
        outputs.joinToString(if (text.contains(';')) ";" else File.pathSeparator)
    }

    fun report(): String = buildString {
        for ((from, to) in mapped) append(from).append(" -> ").append(to.joinToString(", ")).append('\n')
        for (from in unmapped) append(from).append(" -> (no IDEA module, unchanged)\n")
    }

    companion object {
        /** An absolute path followed by `/bin/<name>`, ending at a path separator, quote, whitespace or the end */
        private val PATTERN = Regex("""((?:[A-Za-z]:)?/[^;:"'=\s]*?)/bin/([A-Za-z0-9_-]+)(?=$|[/;:"'\s])""")

        fun toPath(path: String) = FileUtil.toSystemIndependentName(path)
    }
}

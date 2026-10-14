package de.inetsoftware.eclipselaunch.sync

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import de.inetsoftware.eclipselaunch.model.EclipseVariables
import de.inetsoftware.eclipselaunch.model.LaunchKind
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Knowledge about the Eclipse workspace behind the IDEA project: Eclipse projects, their folders and modules. */
class EclipseWorkspace(private val project: Project) {

    /** Eclipse project name → folder, built from the `.project` files in the module content roots. */
    @Volatile
    private var projects: Map<String, File>? = null
    private val userVariables = ConcurrentHashMap<File, Map<String, String>>()

    fun invalidate() {
        projects = null
        userVariables.clear()
    }

    fun projectDir(name: String): File? {
        projects()[name]?.let { return it }
        // Modules may have been added since the index was built
        projects = null
        return projects()[name]
    }

    fun projectName(dir: File): String? = projects().entries.firstOrNull { it.value == dir }?.key ?: readProjectName(dir)

    private fun projects(): Map<String, File> = projects ?: buildIndex().also { projects = it }

    private fun buildIndex(): Map<String, File> {
        val result = HashMap<String, File>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (root in ModuleRootManager.getInstance(module).contentRoots) {
                val dir = File(root.path)
                readProjectName(dir)?.let { result.putIfAbsent(it, dir) }
            }
        }
        return result
    }

    /** Workspace folder of a project folder: the nearest parent containing `.metadata`, else the parent. */
    fun workspaceRoot(projectDir: File): File {
        var dir: File? = projectDir.parentFile
        while (dir != null) {
            if (File(dir, ".metadata").isDirectory) return dir
            dir = dir.parentFile
        }
        return projectDir.parentFile ?: projectDir
    }

    fun variables(launchProject: String?, projectDir: File?): EclipseVariables {
        val root = projectDir?.let { workspaceRoot(it) }
        return EclipseVariables(
            workspaceRoot = root,
            currentProject = launchProject,
            projectLocator = { name -> projectDir(name) ?: root?.resolve(name)?.takeIf { it.isDirectory } },
            userVariables = root?.let { userVariables.computeIfAbsent(it, ::readUserVariables) } ?: emptyMap(),
        )
    }

    /** The IDEA module for an Eclipse project folder: the Gradle source set module `.test`/`.main`, else the holder. */
    fun moduleFor(projectDir: File?, kind: LaunchKind): Module? {
        val holder = holderModule(projectDir ?: return null) ?: return null
        val suffix = if (kind == LaunchKind.JUNIT) "test" else "main"
        return ModuleManager.getInstance(project).findModuleByName("${holder.name}.$suffix") ?: holder
    }

    private fun holderModule(projectDir: File): Module? {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(projectDir) ?: return null
        val modules = ModuleManager.getInstance(project).modules
        return modules.filter { m -> ModuleRootManager.getInstance(m).contentRoots.any { it == vf } }.minByOrNull { it.name.length }
            ?: ProjectFileIndex.getInstance(project).getModuleForFile(vf, false)
    }

    /** IDEA output folder for an Eclipse output folder `<projectDir>/bin/<sourceSet>`. */
    fun outputFor(projectDir: File, sourceSet: String): String? {
        val holder = holderModule(projectDir) ?: return null
        val module = ModuleManager.getInstance(project).findModuleByName("${holder.name}.$sourceSet") ?: return null
        val ext = CompilerModuleExtension.getInstance(module) ?: return null
        val url = if (sourceSet.contains("test", ignoreCase = true)) ext.compilerOutputUrlForTests ?: ext.compilerOutputUrl
        else ext.compilerOutputUrl ?: ext.compilerOutputUrlForTests
        return url?.let { VfsUtilCore.urlToPath(it) }?.let { File(it).path }
    }

    /** A folder of this workspace's Eclipse projects, if [dir] is one. */
    fun isProjectDir(dir: File): Boolean = projects().containsValue(dir) || File(dir, ".project").isFile

    companion object {
        private val NAME = Regex("""<projectDescription>\s*<name>([^<]*)</name>""")

        fun readProjectName(dir: File): String? {
            val file = File(dir, ".project")
            if (!file.isFile) return null
            return try {
                NAME.find(file.readText())?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            } catch (_: Exception) {
                null
            }
        }

        /** Nearest folder containing a `.project` file, starting at [dir]. */
        fun findProjectDir(dir: File?): File? {
            var d = dir
            while (d != null) {
                if (File(d, ".project").isFile) return d
                d = d.parentFile
            }
            return null
        }

        /** String substitution variables defined in the Eclipse preferences of the workspace. */
        fun readUserVariables(workspaceRoot: File): Map<String, String> {
            val prefs = File(workspaceRoot, ".metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.core.variables.prefs")
            if (!prefs.isFile) return emptyMap()
            return try {
                val props = Properties().apply { prefs.inputStream().use { load(it) } }
                val xml = props.getProperty("org.eclipse.core.variables.valueVariables") ?: return emptyMap()
                Regex("""<valueVariable\s[^>]*>""").findAll(xml).mapNotNull { m ->
                    val attrs = Regex("""(\w+)="([^"]*)"""").findAll(m.value).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = attrs["name"] ?: return@mapNotNull null
                    name to de.inetsoftware.eclipselaunch.model.LaunchFile.unescape(attrs["value"] ?: "")
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}

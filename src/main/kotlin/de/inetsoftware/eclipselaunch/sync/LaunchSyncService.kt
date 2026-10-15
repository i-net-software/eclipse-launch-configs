package de.inetsoftware.eclipselaunch.sync

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.config.LaunchLink
import de.inetsoftware.eclipselaunch.model.Keys
import de.inetsoftware.eclipselaunch.run.BuildOutputsBeforeRunTask
import de.inetsoftware.eclipselaunch.model.LaunchFile
import de.inetsoftware.eclipselaunch.model.LaunchKind
import de.inetsoftware.eclipselaunch.model.LaunchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the run configurations of the [EclipseLaunchConfigurationType] in sync with the `.launch` files:
 * file changes (VCS update, Eclipse, editor) are loaded live, changes made in IDEA are written back.
 * All state is accessed on the EDT.
 */
@Service(Service.Level.PROJECT)
class LaunchSyncService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** Last known state of a launch file. */
    class Entry(
        var path: String,
        /** File text as last read or written */
        var text: String,
        /** Settings as last read or written */
        var settings: LaunchSettings,
        /** IDEA values matching [settings], to detect what the user changed */
        var snapshot: IdeaSnapshot,
        /** Name the configuration got from the file */
        var expectedName: String,
        var context: LaunchContext,
        /** Attribute keys of the file */
        var keys: List<String>,
    ) {
        /** The file itself; keeps its identity when renamed or moved */
        var file: VirtualFile? = null
    }

    val workspace = EclipseWorkspace(project)
    private val entries = HashMap<String, Entry>()
    private var suppressEvents = 0
    private var started = false
    private var remapPending = false

    private val runManager get() = RunManager.getInstance(project)
    private val type get() = EclipseLaunchConfigurationType.getInstance()

    fun entry(path: String): Entry? = entries[path]

    /** Starts listening; [scan] loads all launch files of the project once indexing is done. */
    fun start(scan: Boolean = true) {
        if (started) return
        started = true
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFileEvents(events)
        })
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = onChanged(settings)
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = onAdded(settings)
            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = onRemoved(settings)
        })
        connection.subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(project: Project, modules: List<Module>) = scheduleRemap()
            override fun moduleRemoved(project: Project, module: Module) = scheduleRemap()
        })
        if (scan) {
            // A new project gets its content roots from the Gradle sync, long after it was opened
            connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = rescan()
            })
            rescan()
        }
    }

    private var rescanRunning = false
    private var rescanAgain = false

    /** Loads all launch files of the project once indexing is done; coalesces calls while a scan runs. */
    fun rescan(force: Boolean = false) {
        ApplicationManager.getApplication().invokeLater({
            if (force) entries.values.forEach { it.text = "" }
            if (rescanRunning) {
                rescanAgain = true
                return@invokeLater
            }
            rescanRunning = true
            scope.launch {
                try {
                    val files = smartReadAction(project) {
                        FilenameIndex.getAllFilesByExt(project, "launch", GlobalSearchScope.projectScope(project)).filter(::isRelevant)
                    }
                    withContext(Dispatchers.EDT) { syncAll(files) }
                } finally {
                    withContext(NonCancellable + Dispatchers.EDT) {
                        rescanRunning = false
                        if (rescanAgain) {
                            rescanAgain = false
                            rescan()
                        }
                    }
                }
            }
        }, project.disposed)
    }

    /** Loads all given files and removes configurations whose file no longer exists. */
    @RequiresEdt
    fun syncAll(files: Collection<VirtualFile>) {
        workspace.invalidate()
        files.forEach(::sync)
        val known = files.mapTo(HashSet()) { it.path }
        for (settings in ourSettings()) {
            val path = linked(settings)?.link?.path ?: continue
            if (path !in known && !File(path).exists()) {
                entries.remove(path)
                silently { runManager.removeConfiguration(settings) }
            }
        }
    }

    private fun isRelevant(file: VirtualFile): Boolean =
        !file.isDirectory && file.extension == "launch" && !file.path.contains("/.metadata/") &&
            ProjectFileIndex.getInstance(project).isInContent(file)

    // ---------------------------------------------------------------------------------------------------------
    // File → IDEA

    /** Loads a launch file into its run configuration, creating or removing the configuration as needed. */
    @RequiresEdt
    fun sync(file: VirtualFile) {
        val path = file.path
        if (entries[path] == null) {
            // Renamed or moved before its event was handled
            entries.values.firstOrNull { it.file == file && it.path != path }?.let { rekey(it.path, path) }
        }
        val text = readText(file) ?: return
        val entry = entries[path]
        if (entry != null && entry.text == text) return
        val launch = try {
            LaunchFile(text)
        } catch (e: Exception) {
            LOG.info("Cannot read $path: ${e.message}")
            return
        }
        var settings = findSettings(path)
        val kind = LaunchKind.of(launch.type)
        if (kind == null || (settings != null && linked(settings)?.kind != kind)) {
            // Unsupported type, or the type changed: drop the old configuration
            entries.remove(path)
            settings?.let { s -> silently { runManager.removeConfiguration(s) } }
            settings = null
            if (kind == null) return
        }
        val launchSettings = LaunchSettings.read(launch)
        val projectDir = EclipseWorkspace.findProjectDir(File(file.parent.path))
            ?: launchSettings.project?.let { workspace.projectDir(it) }
        val eclipseProject = projectDir?.let { workspace.projectName(it) } ?: launchSettings.project
        val isNew = settings == null
        val rs = settings ?: runManager.createConfiguration(file.nameWithoutExtension, type.factory(kind))
        val config = linked(rs)!!
        val link = config.link ?: LaunchLink(path)
        config.link = link
        val module = link.moduleOverride?.let { ModuleManager.getInstance(project).findModuleByName(it) }
            ?: workspace.moduleFor(projectDir, kind)
        val context = LaunchContext(eclipseProject, projectDir, module, workspace)
        IdeaMapping.apply(config, launchSettings, context)
        val name = uniqueName(file.nameWithoutExtension, eclipseProject, rs)
        rs.name = name
        rs.folderName = eclipseProject
        silently {
            if (isNew) runManager.addConfiguration(rs) else RunManagerImpl.getInstanceImpl(project).fireRunConfigurationChanged(rs)
        }
        if (isNew) {
            // After the defaults ("Build") were added by the run manager
            val tasks = RunManagerImpl.getInstanceImpl(project).getBeforeRunTasks(rs.configuration)
            if (tasks.none { it.providerId == BuildOutputsBeforeRunTask.ID }) rs.configuration.beforeRunTasks = tasks + BuildOutputsBeforeRunTask()
        }
        entries[path] = Entry(path, text, launchSettings, IdeaMapping.snapshot(config, name), name, context, launch.keys).also { it.file = file }
    }

    @RequiresEdt
    private fun remove(path: String) {
        entries.remove(path)
        findSettings(path)?.let { silently { runManager.removeConfiguration(it) } }
    }

    /** A tracked file was renamed or moved: keep its configuration and local settings. */
    @RequiresEdt
    private fun rekey(oldPath: String, newPath: String) {
        val entry = entries.remove(oldPath) ?: return
        val settings = findSettings(oldPath)
        entry.path = newPath
        entry.text = "" // force reload, the name and project may have changed
        entries[newPath] = entry
        settings?.let { linked(it)?.let { c -> c.link = c.link?.copy(path = newPath) } }
    }

    private fun onFileEvents(events: List<VFileEvent>) {
        val changed = LinkedHashSet<String>()
        val removed = LinkedHashSet<String>()
        val moved = ArrayList<Pair<String, String>>()
        val dirs = ArrayList<String>()
        for (e in events) {
            when (e) {
                is VFileDeleteEvent -> removed += e.path
                is VFileMoveEvent -> moved += e.oldPath to e.newPath
                is VFilePropertyChangeEvent -> if (e.isRename) moved += e.oldPath to e.newPath
                is VFileCreateEvent -> if (!e.isDirectory) changed += e.path else if (!isSkippedDir(e.path)) dirs += e.path
                is VFileCopyEvent -> changed += e.newParent.path + "/" + e.newChildName
                is VFileContentChangeEvent -> changed += e.path
            }
        }
        val interesting = { p: String -> p.endsWith(".launch") || entries.keys.any { it.startsWith("$p/") } }
        if (changed.none { it.endsWith(".launch") } && removed.none(interesting) && moved.none { interesting(it.first) || interesting(it.second) } && dirs.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            for ((old, new) in moved) {
                val tracked = entries.keys.filter { it == old || it.startsWith("$old/") }
                tracked.forEach { rekey(it, new + it.removePrefix(old)) }
                if (tracked.isEmpty() && !isSkippedDir(new)) dirs += new
                else tracked.forEach { changed += new + it.removePrefix(old) }
            }
            for (p in removed) entries.keys.filter { it == p || it.startsWith("$p/") }.forEach(::remove)
            val fs = LocalFileSystem.getInstance()
            for (p in changed) fs.findFileByPath(p)?.takeIf(::isRelevant)?.let(::sync)
            scanDirs(dirs)
        }, project.disposed)
    }

    private val pendingDirs = LinkedHashSet<String>()
    private var dirScanRunning = false

    /** Looks for launch files in new or moved folders (VCS update, copy); runs in the background. */
    @RequiresEdt
    private fun scanDirs(dirs: Collection<String>) {
        pendingDirs += dirs
        if (dirScanRunning || pendingDirs.isEmpty()) return
        dirScanRunning = true
        val batch = pendingDirs.toList()
        pendingDirs.clear()
        scope.launch {
            try {
                val found = readAction {
                    val fs = LocalFileSystem.getInstance()
                    val index = ProjectFileIndex.getInstance(project)
                    val result = ArrayList<VirtualFile>()
                    for (p in batch) {
                        val dir = fs.findFileByPath(p) ?: continue
                        if (!dir.isDirectory) {
                            if (isRelevant(dir)) result += dir
                            continue
                        }
                        if (!index.isInContent(dir)) continue
                        VfsUtilCore.iterateChildrenRecursively(dir, { f -> !f.isDirectory || (!isSkippedName(f.name) && !index.isExcluded(f)) }) { f ->
                            if (isRelevant(f)) result += f
                            true
                        }
                    }
                    result
                }
                withContext(Dispatchers.EDT) { found.forEach(::sync) }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    dirScanRunning = false
                    if (pendingDirs.isNotEmpty()) scanDirs(emptyList())
                }
            }
        }
    }

    private fun isSkippedName(name: String) = name in SKIPPED_DIRS || name.startsWith(".git")

    private fun isSkippedDir(path: String) = path.split('/').any(::isSkippedName)

    private fun scheduleRemap() {
        workspace.invalidate()
        if (remapPending) return
        remapPending = true
        ApplicationManager.getApplication().invokeLater({
            remapPending = false
            // Gradle sync adds modules after startup: map configurations that have none (or a stale one)
            for (entry in entries.values) {
                val rs = findSettings(entry.path) ?: continue
                val config = linked(rs) ?: continue
                val wanted = config.link?.moduleOverride?.let { ModuleManager.getInstance(project).findModuleByName(it) }
                    ?: workspace.moduleFor(entry.context.projectDir, config.kind)
                if (wanted != config.moduleBased.configurationModule.module) {
                    config.moduleBased.setModule(wanted)
                    entry.context = entry.context.copy(module = wanted)
                    entry.snapshot = IdeaMapping.snapshot(config, rs.name)
                }
            }
        }, project.disposed)
    }

    // ---------------------------------------------------------------------------------------------------------
    // IDEA → file

    private fun onChanged(rs: RunnerAndConfigurationSettings) {
        if (suppressEvents > 0 || rs.isTemplate) return
        val config = linked(rs) ?: return
        val path = config.link?.path ?: return
        val entry = entries[path] ?: return
        val after = IdeaMapping.snapshot(config, rs.name)
        if (after == entry.snapshot) return
        ApplicationManager.getApplication().invokeLater({ writeBack(rs, config, entry) }, project.disposed)
    }

    @RequiresEdt
    private fun writeBack(rs: RunnerAndConfigurationSettings, config: EclipseLinkedConfiguration, entry: Entry) {
        val before = entry.snapshot
        val after = IdeaMapping.snapshot(config, rs.name)
        if (after == before) return
        val link = config.link ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(entry.path) ?: return
        val sharing = ourSettings().filter { linked(it)?.link?.path == link.path }
        if (sharing.size > 1 && rs.name != entry.expectedName) {
            // A copy made in IDEA: it must not change the original's file
            config.link = null
            offerLaunchFiles()
            return
        }

        if (after.module != before.module) {
            // The module is a local choice; Eclipse keeps its project
            val auto = workspace.moduleFor(entry.context.projectDir, config.kind)?.name
            config.link = link.copy(moduleOverride = after.module.takeIf { it != auto })
            entry.context = entry.context.copy(module = config.moduleBased.configurationModule.module)
        }

        val newSettings = IdeaMapping.toSettings(config, entry.settings, before, after, entry.context)
        if (config.kind == LaunchKind.JUNIT && after.testObject != before.testObject && newSettings.testTarget == entry.settings.testTarget) {
            notify("This kind of test cannot be stored in an Eclipse launch file; \"${rs.name}\" keeps it only until the file changes.", NotificationType.WARNING)
        }
        if (newSettings != entry.settings) {
            val launch = LaunchFile(entry.text)
            LaunchSettings.write(launch, entry.settings, newSettings)
            if (launch.text != entry.text && writeText(file, launch.text)) {
                entry.text = launch.text
                entry.keys = launch.keys
            }
            entry.settings = newSettings
        }
        entry.snapshot = after

        if (after.name != before.name && after.name != entry.expectedName && sharing.size == 1) rename(file, rs, entry, after.name)
    }

    private fun rename(file: VirtualFile, rs: RunnerAndConfigurationSettings, entry: Entry, newName: String) {
        val fileName = "$newName.launch"
        if (newName.isBlank() || newName.any { it in "/\\:*?\"<>|" } || file.parent.findChild(fileName) != null) {
            notify("The Eclipse launch file of \"$newName\" keeps its name ${file.name}.", NotificationType.INFORMATION)
            return
        }
        val oldPath = file.path
        try {
            WriteAction.run<Exception> { file.rename(this, fileName) }
        } catch (e: Exception) {
            notify("Cannot rename ${file.name}: ${e.message}", NotificationType.ERROR)
            return
        }
        entries.remove(oldPath)
        entry.path = file.path
        entry.expectedName = newName
        entries[file.path] = entry
        linked(rs)?.let { it.link = it.link?.copy(path = file.path) }
    }

    private fun onAdded(rs: RunnerAndConfigurationSettings) {
        if (suppressEvents > 0 || rs.isTemplate || linked(rs) == null) return
        if (linkCheckPending) return
        linkCheckPending = true
        // Checked after the run configuration dialog has finished applying all its changes
        ApplicationManager.getApplication().invokeLater({
            linkCheckPending = false
            offerLaunchFiles()
        }, project.disposed)
    }

    private var linkCheckPending = false
    private val offered = HashSet<RunnerAndConfigurationSettings>()

    /** Offers to save configurations created in IDEA as launch files. Nothing is written without the user's click. */
    @RequiresEdt
    private fun offerLaunchFiles() {
        val unlinked = ourSettings().filter { !it.isTemplate && linked(it)?.link == null && it !in offered }
        if (unlinked.isEmpty()) return
        offered += unlinked
        val names = unlinked.joinToString(", ") { "\"${it.name}\"" }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(
            "Not saved as Eclipse launch file",
            "$names ${if (unlinked.size == 1) "has" else "have"} no .launch file yet and ${if (unlinked.size == 1) "is" else "are"} only stored in IDEA.",
            NotificationType.INFORMATION,
        )
        notification.addAction(NotificationAction.createSimpleExpiring(if (unlinked.size == 1) "Create launch file" else "Create launch files") {
            unlinked.filter { runManager.hasSettings(it) && linked(it)?.link == null }.forEach { rs -> createFile(rs, linked(rs)!!) }
        })
        notification.notify(project)
    }

    /** Writes a new launch file for a configuration created in IDEA, next to the Eclipse project of its module. */
    @RequiresEdt
    private fun createFile(rs: RunnerAndConfigurationSettings, config: EclipseLinkedConfiguration) {
        val module = config.moduleBased.configurationModule.module
        val projectDir = module?.let { m -> ModuleRootManager.getInstance(m).contentRoots.firstNotNullOfOrNull { EclipseWorkspace.findProjectDir(File(it.path)) } }
        if (projectDir == null) {
            notify("\"${rs.name}\" has no Eclipse project (no module with a .project file), so no launch file was written.", NotificationType.WARNING)
            return
        }
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir) ?: return
        val fileName = FileUtil.sanitizeFileName(rs.name, false) + ".launch"
        if (dir.findChild(fileName) != null) {
            notify("${projectDir.name}/$fileName already exists; \"${rs.name}\" was not written to a launch file.", NotificationType.WARNING)
            return
        }
        val eclipseProject = workspace.projectName(projectDir) ?: projectDir.name
        val launch = LaunchFile(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<launchConfiguration type=\"${config.kind.typeId}\">\n</launchConfiguration>\n"
        )
        launch.setString(Keys.PROJECT, eclipseProject)
        if (config.kind == LaunchKind.JUNIT) {
            launch.setBoolean("org.eclipse.jdt.junit.KEEPRUNNING_ATTR", false)
            launch.setString(Keys.JUNIT_TEST_KIND, junitKind(module!!))
        }
        val empty = LaunchSettings.read(launch)
        val context = LaunchContext(eclipseProject, projectDir, module, workspace)
        val after = IdeaMapping.snapshot(config, rs.name)
        val settings = IdeaMapping.toSettings(config, empty, null, after, context)
        LaunchSettings.write(launch, empty, settings)
        val file = try {
            WriteAction.compute<VirtualFile, Exception> {
                dir.createChildData(this, fileName).also { it.setBinaryContent(launch.text.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            notify("Cannot create $fileName: ${e.message}", NotificationType.ERROR)
            return
        }
        config.link = LaunchLink(file.path, rewriteOutput = true)
        rs.folderName = eclipseProject
        entries[file.path] = Entry(file.path, launch.text, settings, after, rs.name, context, launch.keys).also { it.file = file }
    }

    /** JUnit 5 if the Jupiter API is on the module's class path (no index needed). */
    private fun junitKind(module: Module): String {
        val jupiter = OrderEnumerator.orderEntries(module).recursively().librariesOnly().classes().roots.any { it.name.startsWith("junit-jupiter-api") }
        return if (jupiter) "org.eclipse.jdt.junit.loader.junit5" else "org.eclipse.jdt.junit.loader.junit4"
    }

    private fun onRemoved(rs: RunnerAndConfigurationSettings) {
        if (suppressEvents > 0 || rs.isTemplate) return
        val path = linked(rs)?.link?.path ?: return
        ApplicationManager.getApplication().invokeLater({ afterRemoved(rs.name, path) }, project.disposed)
    }

    @RequiresEdt
    private fun afterRemoved(name: String, path: String) {
        // The run configuration dialog may replace a configuration by its edited copy
        if (findSettings(path) != null) return
        entries.remove(path) ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(
            "Eclipse launch file kept",
            "\"$name\" was removed from the run configurations, but ${file.name} still exists and comes back on the next start.",
            NotificationType.INFORMATION,
        )
        notification.addAction(NotificationAction.createSimpleExpiring("Delete file") {
            try {
                WriteAction.run<Exception> { if (file.isValid) file.delete(this) }
            } catch (e: Exception) {
                notify("Cannot delete ${file.name}: ${e.message}", NotificationType.ERROR)
            }
        })
        notification.addAction(NotificationAction.createSimpleExpiring("Restore") { if (file.isValid) sync(file) })
        notification.notify(project)
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun readText(file: VirtualFile): String? {
        val fdm = FileDocumentManager.getInstance()
        fdm.getCachedDocument(file)?.takeIf { fdm.isDocumentUnsaved(it) }?.let { return it.text }
        return try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: Exception) {
            LOG.info("Cannot read ${file.path}: ${e.message}")
            null
        }
    }

    private fun writeText(file: VirtualFile, text: String): Boolean = try {
        WriteAction.run<Exception> {
            val fdm = FileDocumentManager.getInstance()
            val document = fdm.getCachedDocument(file)
            if (document != null) {
                document.setText(StringUtil.convertLineSeparators(text))
                fdm.saveDocument(document)
            } else {
                file.setBinaryContent(text.toByteArray(file.charset))
            }
        }
        true
    } catch (e: Exception) {
        notify("Cannot write ${file.name}: ${e.message}", NotificationType.ERROR)
        false
    }

    private fun uniqueName(base: String, folder: String?, self: RunnerAndConfigurationSettings): String {
        val taken = runManager.allSettings.filter { it !== self }.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        val withFolder = if (folder != null) "$base ($folder)" else base
        return RunManager.suggestUniqueName(withFolder, taken)
    }

    private fun ourSettings(): List<RunnerAndConfigurationSettings> = runManager.getConfigurationSettingsList(type)

    private fun findSettings(path: String) = ourSettings().firstOrNull { linked(it)?.link?.path == path }

    private fun linked(rs: RunnerAndConfigurationSettings) = rs.configuration as? EclipseLinkedConfiguration

    private inline fun silently(action: () -> Unit) {
        suppressEvents++
        try {
            action()
        } finally {
            suppressEvents--
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(content, type).notify(project)
    }

    override fun dispose() {}

    companion object {
        const val NOTIFICATION_GROUP = "Eclipse Launch Configs"
        private val SKIPPED_DIRS = setOf(".metadata", "node_modules", ".svn", ".gradle", ".idea", "build", "bin", "out", ".kotlin")
        private val LOG = logger<LaunchSyncService>()

        fun getInstance(project: Project): LaunchSyncService = project.service()
    }
}

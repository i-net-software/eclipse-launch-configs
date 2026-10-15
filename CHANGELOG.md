# Changelog

## [Unreleased]

## [1.0.0] - 2026-10-15

### Added
- Eclipse `.launch` files (JUnit, Java Application) in project folders appear live as **Eclipse Launch** run configurations, grouped by the Eclipse project of the file; picked up again whenever a Gradle sync changes the modules
- A launch runs in the IDEA module of its Eclipse project (`PROJECT_ATTR`), mapped by folder, including Gradle source set modules
- Changes made in IntelliJ IDEA are written back to the `.launch` file, keeping unknown attributes, order and formatting; arguments that only differ in line breaks are no change
- Changes to the file (VCS update, Eclipse, editor) are loaded immediately; renames and deletions follow
- Eclipse variables (`${workspace_loc}`, `${project_loc}`, `${env_var}`, user defined workspace variables, …) are resolved at launch time
- Eclipse output folders (`bin/main`, `bin/test`) are replaced by the output of the IDEA modules compiling those sources, and these modules are built before the launch (incrementally)
- Launch report in the log and a warning for folders without IDEA module
- `.launch` file type with icon; Run/Debug from the file's context menu; **Run → Reload Eclipse Launch Files**
- Configurations created in IDEA are saved as `.launch` file only on request

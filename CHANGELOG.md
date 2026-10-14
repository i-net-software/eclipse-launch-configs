# Changelog

## [Unreleased]

### Added
- Eclipse `.launch` files (JUnit, Java Application) in project folders appear live as run configurations
- Changes made in IntelliJ IDEA are written back to the `.launch` file, keeping unknown attributes and formatting
- Eclipse variables (`${workspace_loc}`, `${project_loc}`, `${env_var}`, …) are resolved at launch time
- Optional rewrite of Eclipse output folders (`bin/main`, `bin/test`) to the IDEA module output

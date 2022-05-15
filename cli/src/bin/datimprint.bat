@ECHO OFF
REM ~{project.name} ~{project.version} Launcher
REM Copyright (c) ~{project.inceptionYear}-~{build.year} ~{project.organization.name}
REM
REM This batch script expects to find the executable JAR file
REM in the same directory as the script.

java -jar %~dp0~{project.artifactId}-~{project.version}-exe.jar %*

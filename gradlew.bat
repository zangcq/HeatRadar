@echo off
setlocal

:: Minimal Gradle wrapper launcher.
:: Replace gradle\wrapper\gradle-wrapper.jar with the real Gradle wrapper jar
:: before executing.

set DIRNAME=%~dp0
java -jar "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" %*

endlocal

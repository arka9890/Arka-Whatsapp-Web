@echo off
set APP_HOME=%~dp0
set JAVACMD=java
set GRADLE_OPTS=-Dorg.gradle.daemon=false -Xmx2048m
"%JAVACMD%" %GRADLE_OPTS% -cp "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

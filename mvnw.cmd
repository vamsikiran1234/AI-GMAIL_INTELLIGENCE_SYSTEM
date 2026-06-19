@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@echo off
setlocal

set DIR=%~dp0
set MAVEN_BASEDIR=%DIR%..
set WRAPPER_JAR="%MAVEN_BASEDIR%\.mvn\wrapper\maven-wrapper.jar"
set WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

set DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.1.1/maven-wrapper-3.1.1.jar"

if exist %WRAPPER_JAR% (
    echo Found %WRAPPER_JAR%
) else (
    echo Couldn't find %WRAPPER_JAR%, downloading it ...
    mkdir %MAVEN_BASEDIR%\.mvn\wrapper
    powershell -Command "(New-Object Net.WebClient).DownloadFile(%DOWNLOAD_URL%, %WRAPPER_JAR%)"
    if errorlevel 1 goto error
)

set JAVA_HOME=%MAVEN_BASEDIR%\jvm
if not exist "%JAVA_HOME%" set JAVA_HOME=

set JAVACMD="%JAVA_HOME%\bin\java.exe"
if not exist %JAVACMD% set JAVACMD=java

%JAVACMD% -Dmaven.multiModuleProjectDirectory="%MAVEN_BASEDIR%" ^
    -classpath %WRAPPER_JAR% ^
    "-Dmaven.home=%MAVEN_BASEDIR%" ^
    %WRAPPER_LAUNCHER% %*

goto end

:error
set ERROR_CODE=1

:end
@endlocal & exit /b %ERROR_CODE%
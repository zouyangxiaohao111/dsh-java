@echo off
rem dshj.bat - dsh-java CLI entry for Windows cmd/PowerShell. Same shape as the bash dshj.
rem   dshj [--profile <name>] boot [appArgs...]
rem   dshj web|headless|cli [boot] [appArgs...]
rem   dshj plugin --profile <name> add <spec>
rem   dshj --help
rem
rem NOTE: keep this file pure ASCII -- cmd on a GBK codepage mangles UTF-8 multibyte.
setlocal enabledelayedexpansion
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "ARGS="
:loop
if "%~1"=="" goto run
set "ARGS=!ARGS! "%~1""
shift
goto loop
:run
if not "!ARGS!"=="" set "ARGS=!ARGS:~1!"
call "%DIR%\gradlew.bat" -p "%DIR%" -q :dsh-host:run --args="!ARGS!"
exit /b %ERRORLEVEL%

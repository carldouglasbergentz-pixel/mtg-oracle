@echo off
rem The MTG Oracle command line, from the newest snapshot `gradlew :app:installLocal`
rem made: `mtg card Sol Ring`, `mtg search t:instant c:u --json`, `mtg help`.
setlocal
set "DIST=%~dp0dist"
if not exist "%DIST%\current" (
    echo No snapshot yet. In %~dp0 run: gradlew :app:installLocal
    exit /b 1
)
set /p SNAPSHOT=<"%DIST%\current"
rem Oracle text is full of em-dashes: the console reads UTF-8 while this runs.
for /f "tokens=2 delims=:." %%c in ('chcp') do set "PAGE=%%c"
chcp 65001 >nul
call "%DIST%\%SNAPSHOT%\run.cmd" cli %*
set "CODE=%ERRORLEVEL%"
chcp %PAGE% >nul
exit /b %CODE%

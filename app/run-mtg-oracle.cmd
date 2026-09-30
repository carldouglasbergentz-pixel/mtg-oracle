@echo off
rem Plays MTG Oracle from the newest snapshot `gradlew :app:installLocal` made
rem (app\dist\current names it). Not `gradlew run`: builds replace the class
rem files under a running game, and the next class it loads is gone.
setlocal
set "DIST=%~dp0dist"
if not exist "%DIST%\current" (
    echo No snapshot yet. In %~dp0 run: gradlew :app:installLocal
    exit /b 1
)
set /p SNAPSHOT=<"%DIST%\current"
call "%DIST%\%SNAPSHOT%\run.cmd" %*

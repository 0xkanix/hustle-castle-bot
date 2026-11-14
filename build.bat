@echo off
title Hustle Castle Bot bauen
cd /d "%~dp0"
call mvn clean package
if errorlevel 1 (
  echo.
  echo Der Bau ist fehlgeschlagen. Bitte die Meldungen oben pruefen.
  pause
  exit /b 1
)
echo.
echo Fertig. Jetzt capture.bat fuer die Vorlagen, danach run.bat.
pause

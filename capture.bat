@echo off
title Vorlagen aufnehmen
cd /d "%~dp0"

REM Nimmt Bildausschnitte aus dem Spiel auf und legt sie unter templates ab.
REM
REM Warum hier so viel steht: Auf diesem Rechner sind ZWEI Java-Versionen
REM installiert -- ein aktuelles 21 und ein uraltes 8 von Oracle. Der Bot
REM braucht 21. Erwischt der Doppelklick das alte, bricht das Programm
REM sofort ab, und frueher war das Fenster weg, bevor man die Meldung
REM lesen konnte.
REM
REM Deshalb wird Java jetzt in dieser Reihenfolge gesucht:
REM   1. JAVA_HOME, falls gesetzt
REM   2. der bekannte Ordner von Java 21
REM   3. das java, das im Suchpfad steht
REM
REM Und das Fenster bleibt am Ende immer offen.

if not exist "target\hustle-bot.jar" (
  echo.
  echo   Das Programm wurde noch nicht gebaut.
  echo   Bitte zuerst build.bat ausfuehren.
  echo.
  pause
  exit /b 1
)

set "JAVABIN="

if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVABIN=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVABIN (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
    if exist "%%D\bin\java.exe" set "JAVABIN=%%D\bin\java.exe"
  )
)

if not defined JAVABIN (
  where java >nul 2>&1
  if not errorlevel 1 set "JAVABIN=java"
)

if not defined JAVABIN (
  echo.
  echo   Java wurde nicht gefunden.
  echo   Der Bot braucht Java 21. Herunterladen bei adoptium.net
  echo.
  pause
  exit /b 1
)

echo Benutzt: %JAVABIN%
echo.

"%JAVABIN%" -cp target\hustle-bot.jar de.hustlebot.tools.TemplateCapturer templates

if errorlevel 1 (
  echo.
  echo   Das Aufnahmefenster hat sich mit einem Fehler beendet.
  echo   Die Meldung steht oben. Am haeufigsten:
  echo.
  echo   "UnsupportedClassVersionError"  = falsche Java-Version
  echo   "ClassNotFoundException"        = build.bat ausfuehren
  echo.
)

echo.
echo   Fenster kann geschlossen werden.
pause

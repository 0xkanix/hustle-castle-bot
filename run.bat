@echo off
title Hustle Castle Bot
cd /d "%~dp0"
if not exist "target\hustle-bot.jar" (
  echo Das Programm wurde noch nicht gebaut.
  echo Bitte zuerst build.bat ausfuehren.
  pause
  exit /b 1
)
rem ---------------------------------------------------------------------
rem  -Xmx512m (21.11.: 192m reichte fuer 3.151 nicht -> OutOfMemoryError; 512m ist nur die Obergrenze)  --  die Speicherbremse
rem
rem  Ohne diese Angabe darf sich Java ein Viertel des gesamten
rem  Arbeitsspeichers nehmen. Es BRAUCHT das nicht -- der Bot haelt nur ein
rem  paar Bilder und die Vorlagen im Speicher, zusammen keine hundert
rem  Megabyte. Aber Java raeumt erst auf, wenn ihm der Platz ausgeht, und
rem  wenn der Platz erst bei vier Gigabyte ausgeht, raeumt es eben nie.
rem
rem  Am 6. September standen deswegen 3,3 Gigabyte im Task-Manager. Mit der
rem  Bremse raeumt Java frueh und oft, und es bleibt bei einem Bruchteil.
rem
rem  -XX:+UseSerialGC  --  der schlichte Aufraeumer
rem
rem  Java bringt mehrere mit; der voreingestellte legt sich fuer jeden
rem  Prozessorkern eigene Bereiche an und braucht dafuer selbst Speicher.
rem  Fuer ein Programm dieser Groesse ist der schlichte der schnellere und
rem  sparsamere.
rem ---------------------------------------------------------------------
java -Xmx512m -Xms32m -XX:+UseSerialGC -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -XX:ReservedCodeCacheSize=48m -Xss512k -jar target\hustle-bot.jar
pause

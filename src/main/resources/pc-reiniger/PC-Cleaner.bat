@echo off
rem PC-Cleaner fuer den Hustle Castle Bot -- startet das Fenster mit Admin-Rechten (ohne blaues Konsolenfenster).
powershell -NoProfile -WindowStyle Hidden -Command "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"%~dp0PC-Cleaner.ps1\"'"

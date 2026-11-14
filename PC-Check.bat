@echo off
title Hustle Castle Bot - PC-Check
REM ---------------------------------------------------------------------
REM  Prueft, ob dieser Rechner fuer den Hustle Castle Bot passt.
REM  Checks whether this PC is fit for the Hustle Castle Bot.
REM
REM  AENDERT NICHTS am Rechner. Schreibt nur das Ergebnis in die Datei
REM  PC-Check-Ergebnis.txt neben diese Datei (zum Nachlesen).
REM  CHANGES NOTHING on the PC. Only writes the result to
REM  PC-Check-Ergebnis.txt next to this file (for your own reference).
REM
REM  Der Ablauf steht unten als PowerShell (nach der Startmarke) -- wie bei
REM  Install.bat. Der eigene Pfad geht ueber eine Umgebungsvariable
REM  hinein, nicht in den Befehl.
REM ---------------------------------------------------------------------
set "HB_SELF=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText($env:HB_SELF); $i=$s.IndexOf([char]10+'#'+'PS-START'); Invoke-Expression $s.Substring($i)"
echo.
if not "%HB_NOPAUSE%"=="1" pause
exit /b

#PS-START
$ErrorActionPreference = 'Continue'
function T($de, $en) { if ($global:HBEN) { $en } else { $de } }
$self = $env:HB_SELF
$hier = Split-Path -Parent $self

# Sprache: HB_LANG=de|en ueberspringt die Frage (so ruft der Installer den Check auf).
if ($env:HB_LANG -eq 'en') { $global:HBEN = $true }
elseif ($env:HB_LANG -eq 'de') { $global:HBEN = $false }
else {
    Write-Host ''
    $wahl = Read-Host ' Sprache / Language:  1 = Deutsch   2 = English   [2]'
    $global:HBEN = ($wahl.Trim() -ne '1')   # seit 3.1 ist Englisch der Standard (Autor 11.10.)
}

$zeilen = New-Object System.Collections.Generic.List[string]
$global:fehler = 0
$global:hinweise = 0

function Zeile($art, $text) {
    # art: OK, WARN, FEHLER, INFO
    $marke = switch ($art) { 'OK' { '[ OK ]' } 'WARN' { '[ !! ]' } 'FEHLER' { '[ XX ]' } default { '[ i  ]' } }
    $farbe = switch ($art) { 'OK' { 'Green' } 'WARN' { 'Yellow' } 'FEHLER' { 'Red' } default { 'Gray' } }
    if ($art -eq 'FEHLER') { $global:fehler++ }
    if ($art -eq 'WARN') { $global:hinweise++ }
    Write-Host (' ' + $marke + ' ' + $text) -ForegroundColor $farbe
    $zeilen.Add($marke + ' ' + $text)
}
function Kopf($text) {
    Write-Host ''
    Write-Host (' ' + $text) -ForegroundColor White
    $zeilen.Add('')
    $zeilen.Add($text)
}

Write-Host ''
Write-Host ' Hustle Castle Bot - PC-Check'
Write-Host ' ============================'
$zeilen.Add('Hustle Castle Bot - PC-Check')
$zeilen.Add((Get-Date).ToString('yyyy-MM-dd HH:mm'))

# ---- 1. Windows ---------------------------------------------------------------
Kopf (T '1. Windows' '1. Windows')
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $build = [int]$os.BuildNumber
    $name = $os.Caption.Trim()
    if ($build -ge 22000) { Zeile 'OK' ("$name (Build $build)") }
    elseif ($build -ge 10240) { Zeile 'OK' ("$name (Build $build)") }
    else { Zeile 'FEHLER' ((T 'Zu altes Windows: ' 'Windows is too old: ') + "$name. " + (T 'Gebraucht wird Windows 10 oder 11.' 'Windows 10 or 11 is required.')) }
    if ([Environment]::Is64BitOperatingSystem) { Zeile 'OK' (T '64-Bit-Windows' '64-bit Windows') }
    else { Zeile 'FEHLER' (T '32-Bit-Windows -- der Bot braucht 64 Bit.' '32-bit Windows -- the bot needs 64 bit.') }
} catch {
    Zeile 'WARN' ((T 'Windows-Version nicht lesbar: ' 'Could not read the Windows version: ') + $_.Exception.Message)
}
if ($PSVersionTable.PSVersion.Major -ge 5) { Zeile 'OK' ('PowerShell ' + $PSVersionTable.PSVersion.ToString()) }
else { Zeile 'FEHLER' ((T 'PowerShell ist zu alt: ' 'PowerShell is too old: ') + $PSVersionTable.PSVersion.ToString()) }

# ---- 2. Bildschirm --------------------------------------------------------------
# Der Bot stellt das Spielfenster auf 1175 x 809 und dockt es oben rechts an.
Kopf (T '2. Bildschirm' '2. Screen')
try {
    Add-Type -AssemblyName System.Windows.Forms
    $b = [System.Windows.Forms.Screen]::PrimaryScreen
    $w = $b.WorkingArea.Width; $h = $b.WorkingArea.Height
    $text = "$($b.Bounds.Width) x $($b.Bounds.Height)" + (T ' (nutzbar ' ' (usable ') + "$w x $h)"
    if ($w -ge 1175 -and $h -ge 809) {
        if ($w -ge 1600) { Zeile 'OK' ((T 'Hauptbildschirm ' 'Main screen ') + $text) }
        else { Zeile 'OK' ((T 'Hauptbildschirm ' 'Main screen ') + $text + (T ' -- reicht; neben dem Spielfenster bleibt wenig Platz.' ' -- enough; little room is left beside the game window.')) }
    } else {
        Zeile 'FEHLER' ((T 'Hauptbildschirm ' 'Main screen ') + $text + (T ' -- zu klein. Das Spielfenster braucht 1175 x 809.' ' -- too small. The game window needs 1175 x 809.'))
    }
    $anzahl = [System.Windows.Forms.Screen]::AllScreens.Count
    if ($anzahl -gt 1) { Zeile 'INFO' ("$anzahl " + (T 'Bildschirme -- der Bot spielt auf dem Hauptbildschirm.' 'screens -- the bot plays on the main screen.')) }
} catch {
    Zeile 'WARN' ((T 'Bildschirmgroesse nicht lesbar: ' 'Could not read the screen size: ') + $_.Exception.Message)
}
try {
    $dpi = (Get-ItemProperty -Path 'HKCU:\Control Panel\Desktop\WindowMetrics' -Name AppliedDPI -ErrorAction Stop).AppliedDPI
    $prozent = [int][math]::Round($dpi / 96 * 100)
    if ($prozent -eq 100) { Zeile 'OK' ((T 'Skalierung ' 'Scaling ') + "$prozent %") }
    else { Zeile 'WARN' ((T 'Skalierung ' 'Scaling ') + "$prozent % " + (T '-- getestet ist 100 %. Erkennt der Bot Knoepfe nicht, in den Windows-Anzeigeeinstellungen auf 100 % stellen.' '-- tested with 100 %. If the bot does not find buttons, set scaling to 100 % in the Windows display settings.')) }
} catch {
    Zeile 'INFO' (T 'Skalierung nicht lesbar (meist 100 %).' 'Scaling not readable (usually 100 %).')
}

# ---- 3. Spiel und Launcher -----------------------------------------------------
Kopf (T '3. Hustle Castle und MY.GAMES Launcher' '3. Hustle Castle and MY.GAMES Launcher')
$launcher = 'C:\MY.GAMES\MY.GAMES Launcher\MGL.exe'
if (Test-Path -LiteralPath $launcher) {
    Zeile 'OK' ((T 'Launcher gefunden: ' 'Launcher found: ') + $launcher)
} else {
    $woanders = $null
    foreach ($lw in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
        $p = Join-Path $lw.Root 'MY.GAMES\MY.GAMES Launcher\MGL.exe'
        if (Test-Path -LiteralPath $p) { $woanders = $p; break }
    }
    if ($woanders) {
        Zeile 'WARN' ((T 'Launcher liegt unter ' 'The launcher is at ') + $woanders + (T ' -- der Bot erwartet ihn unter C:\MY.GAMES. Er spielt trotzdem, kann das Spiel aber nicht selbst starten.' ' -- the bot expects it at C:\MY.GAMES. It still plays, but cannot start the game by itself.'))
    } else {
        Zeile 'WARN' (T 'MY.GAMES Launcher nicht gefunden (C:\MY.GAMES\MY.GAMES Launcher). Bitte Hustle Castle ueber den MY.GAMES Launcher installieren.' 'MY.GAMES Launcher not found (C:\MY.GAMES\MY.GAMES Launcher). Please install Hustle Castle through the MY.GAMES Launcher.')
    }
}
$spielLaeuft = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'Hustle Castle' } | Select-Object -First 1
if ($spielLaeuft) { Zeile 'OK' (T 'Hustle Castle laeuft gerade.' 'Hustle Castle is running right now.') }
else { Zeile 'INFO' (T 'Hustle Castle laeuft gerade nicht (fuer den Check egal).' 'Hustle Castle is not running right now (does not matter for the check).') }

# ---- 4. Texterkennung von Windows ------------------------------------------------
# Der Bot liest Zahlen und Namen mit der in Windows eingebauten Texterkennung.
Kopf (T '4. Texterkennung (in Windows eingebaut)' '4. Text recognition (built into Windows)')
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime -ErrorAction SilentlyContinue
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    $sprachen = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag })
    if ($sprachen.Count -gt 0) {
        Zeile 'OK' ((T 'Vorhanden fuer: ' 'Available for: ') + ($sprachen -join ', '))
        if (-not ($sprachen | Where-Object { $_ -like 'en*' -or $_ -like 'de*' })) {
            Zeile 'WARN' (T 'Weder Deutsch noch Englisch dabei -- in den Windows-Einstellungen unter Sprache "Englisch" hinzufuegen.' 'Neither German nor English present -- add "English" under Language in the Windows settings.')
        }
    } else {
        Zeile 'FEHLER' (T 'Keine Sprache fuer die Texterkennung installiert. In den Windows-Einstellungen unter Zeit und Sprache -> Sprache eine Sprache mit "Texterkennung" hinzufuegen.' 'No language for text recognition installed. In the Windows settings under Time and language -> Language add a language with "Optical character recognition".')
    }
} catch {
    Zeile 'WARN' ((T 'Texterkennung liess sich nicht pruefen: ' 'Could not check text recognition: ') + $_.Exception.Message)
}

# ---- 5. Speicher --------------------------------------------------------------------
Kopf (T '5. Speicher' '5. Memory and disk')
try {
    $ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
    if ($ram -ge 7.5) { Zeile 'OK' ((T 'Arbeitsspeicher: ' 'RAM: ') + "$ram GB") }
    elseif ($ram -ge 3.5) { Zeile 'WARN' ((T 'Arbeitsspeicher: ' 'RAM: ') + "$ram GB " + (T '-- geht, 8 GB sind besser (Spiel und Bot laufen zusammen).' '-- works, 8 GB is better (game and bot run together).')) }
    else { Zeile 'FEHLER' ((T 'Arbeitsspeicher: ' 'RAM: ') + "$ram GB " + (T '-- zu wenig.' '-- too little.')) }
} catch { Zeile 'INFO' (T 'Arbeitsspeicher nicht lesbar.' 'RAM not readable.') }
try {
    $c = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $frei = [math]::Round($c.FreeSpace / 1GB, 1)
    if ($frei -ge 1) { Zeile 'OK' ((T 'Frei auf C: ' 'Free on C: ') + "$frei GB") }
    else { Zeile 'FEHLER' ((T 'Frei auf C: ' 'Free on C: ') + "$frei GB " + (T '-- der Bot braucht rund 500 MB, dazu Protokolle.' '-- the bot needs about 500 MB plus logs.')) }
} catch { Zeile 'INFO' (T 'Freier Platz nicht lesbar.' 'Free space not readable.') }

# ---- 6. Internet / Discord -----------------------------------------------------------
Kopf (T '6. Discord erreichbar' '6. Discord reachable')
foreach ($ziel in 'discord.com', 'gateway.discord.gg') {
    $ok = $false
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $warte = $tcp.BeginConnect($ziel, 443, $null, $null)
        if ($warte.AsyncWaitHandle.WaitOne(4000) -and $tcp.Connected) { $ok = $true }
        $tcp.Close()
    } catch { $ok = $false }
    if ($ok) { Zeile 'OK' $ziel }
    else { Zeile 'WARN' ($ziel + (T ' nicht erreichbar -- ohne Discord laesst sich der Bot nicht steuern (Internet, Firewall?).' ' not reachable -- without Discord the bot cannot be controlled (internet, firewall?).')) }
}

# ---- 7. Energiesparen ----------------------------------------------------------------
Kopf (T '7. Energiesparen' '7. Power saving')
try {
    $aus = & powercfg /query SCHEME_CURRENT SUB_SLEEP STANDBYIDLE 2>$null
    $zeileAC = $aus | Where-Object { $_ -match '0x[0-9a-fA-F]{8}' } | Select-Object -Last 2 | Select-Object -First 1
    if ($zeileAC -match '0x([0-9a-fA-F]{8})') {
        $sek = [Convert]::ToInt32($Matches[1], 16)
        if ($sek -eq 0) { Zeile 'OK' (T 'Der PC geht am Netz nie von selbst in den Ruhezustand.' 'On mains power the PC never goes to sleep by itself.') }
        else { Zeile 'WARN' ((T 'Der PC schlaeft nach ' 'The PC goes to sleep after ') + [int]($sek / 60) + (T ' Minuten ohne Eingabe ein. Solange der Bot klickt, bleibt er wach -- in langen Wartepausen (z. B. bis zum Clan-Krieg) kann er einschlafen. Dafuer in den Windows-Energieoptionen auf "Nie" stellen.' ' minutes without input. While the bot clicks it stays awake -- in long waiting breaks (e.g. until the clan war) it can fall asleep. For that, set it to "Never" in the Windows power options.')) }
    } else { Zeile 'INFO' (T 'Energie-Einstellung nicht lesbar.' 'Power setting not readable.') }
} catch { Zeile 'INFO' (T 'Energie-Einstellung nicht lesbar.' 'Power setting not readable.') }

# ---- 8. Der Bot selbst (nur wenn der Check im Bot-Ordner liegt) --------------------------
if (Test-Path -LiteralPath (Join-Path $hier 'app\hustle-bot.jar')) {
    Kopf (T '8. Bot-Ordner' '8. Bot folder')
    foreach ($datei in 'HustleBot.exe', 'Settings.exe', 'Capture-Images.exe', 'runtime\bin\javaw.exe', 'texterkennung.ps1', 'config.properties') {
        if (Test-Path -LiteralPath (Join-Path $hier $datei)) { Zeile 'OK' $datei }
        else { Zeile 'FEHLER' ($datei + (T ' fehlt -- Paket bitte neu auspacken.' ' is missing -- please unpack the package again.')) }
    }
    $bilder = @(Get-ChildItem -LiteralPath (Join-Path $hier 'templates') -Recurse -Filter '*.png' -ErrorAction SilentlyContinue).Count
    if ($bilder -gt 100) { Zeile 'OK' ("templates: $bilder " + (T 'Bilder' 'images')) }
    else { Zeile 'WARN' ("templates: $bilder " + (T 'Bilder -- das ist wenig, Paket neu auspacken?' 'images -- that is few, unpack the package again?')) }
}

# ---- Ergebnis -----------------------------------------------------------------------------
Write-Host ''
Write-Host ' ----------------------------------------------------------------'
if ($global:fehler -eq 0 -and $global:hinweise -eq 0) {
    $urteil = T 'ERGEBNIS: Alles passt. Der Bot sollte auf diesem PC laufen.' 'RESULT: Everything fits. The bot should run on this PC.'
    Write-Host (' ' + $urteil) -ForegroundColor Green
} elseif ($global:fehler -eq 0) {
    $urteil = (T 'ERGEBNIS: Der Bot sollte laufen. Hinweise oben beachten: ' 'RESULT: The bot should run. Mind the notes above: ') + $global:hinweise
    Write-Host (' ' + $urteil) -ForegroundColor Yellow
} else {
    $urteil = (T 'ERGEBNIS: So laeuft der Bot noch nicht. Zu beheben: ' 'RESULT: The bot will not run like this yet. To fix: ') + $global:fehler + (T ', Hinweise: ' ', notes: ') + $global:hinweise
    Write-Host (' ' + $urteil) -ForegroundColor Red
}
$zeilen.Add('')
$zeilen.Add($urteil)

# Ergebnis als Datei (zum Nachlesen). Klappt es neben der Datei nicht, dann im Temp-Ordner.
$datei = Join-Path $hier 'PC-Check-Ergebnis.txt'
try {
    Set-Content -LiteralPath $datei -Value $zeilen -Encoding UTF8 -ErrorAction Stop
} catch {
    $datei = Join-Path $env:TEMP 'PC-Check-Ergebnis.txt'
    try { Set-Content -LiteralPath $datei -Value $zeilen -Encoding UTF8 -ErrorAction Stop } catch { $datei = $null }
}
if ($datei) {
    Write-Host ((T ' Gespeichert in: ' ' Saved to: ') + $datei)
    Write-Host (T ' Bei Fragen diese Datei einem Issue auf GitHub beilegen.' ' If you have questions, attach this file to an issue on GitHub.')
}

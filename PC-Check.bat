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
$hcExe = $null
foreach ($lw in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
    $kand = Join-Path $lw.Root 'MY.GAMES\Hustle Castle\HC.exe'
    if (Test-Path -LiteralPath $kand) { $hcExe = $kand; break }
}
if ($hcExe) { Zeile 'OK' ((T 'Spiel gefunden: ' 'Game found: ') + $hcExe) }
else { Zeile 'WARN' (T 'Hustle Castle (HC.exe) nicht im Standardordner gefunden. -> Spiel ueber den MY.GAMES Launcher installieren und einmal starten.' 'Hustle Castle (HC.exe) not found in the default folder. -> Install the game through the MY.GAMES Launcher and start it once.') }
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
Kopf (T '6. Discord erreichbar (nur fuer die Discord-Steuerung)' '6. Discord reachable (only for Discord control)')
foreach ($ziel in 'discord.com', 'gateway.discord.gg') {
    $ok = $false
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $warte = $tcp.BeginConnect($ziel, 443, $null, $null)
        if ($warte.AsyncWaitHandle.WaitOne(4000) -and $tcp.Connected) { $ok = $true }
        $tcp.Close()
    } catch { $ok = $false }
    if ($ok) { Zeile 'OK' $ziel }
    else { Zeile 'INFO' ($ziel + (T ' nicht erreichbar -- nur wichtig, wenn Sie den Bot per Discord steuern (Internet, Firewall?). Das Bot-Programm geht auch ohne.' ' not reachable -- only matters if you control the bot through Discord (internet, firewall?). The bot program works without it.')) }
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

# ---- Bot-Ordner suchen (fuer die Sicherheits-Pruefungen) ----------------------------------
$botOrdner = $null
if (Test-Path -LiteralPath (Join-Path $hier 'HustleBot.exe')) { $botOrdner = $hier }
elseif (Test-Path -LiteralPath 'C:\HustleBot') { $botOrdner = 'C:\HustleBot' }

# ---- 9. VC++-Laufzeit (die Texterkennung braucht sie) ------------------------------------------
Kopf (T '8. Visual C++ Laufzeit' '8. Visual C++ runtime')
try {
    $sys32 = Join-Path $env:SystemRoot 'System32'
    $fehlt = @('vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll', 'msvcp140_1.dll') | Where-Object { -not (Test-Path -LiteralPath (Join-Path $sys32 $_)) }
    if ($fehlt.Count -eq 0) { Zeile 'OK' (T 'Visual C++ Laufzeit (2015-2022, x64) ist installiert.' 'Visual C++ runtime (2015-2022, x64) is installed.') }
    else {
        Zeile 'WARN' ((T 'Visual C++ Laufzeit fehlt (' 'Visual C++ runtime is missing (') + ($fehlt -join ', ') + (T '). Ohne sie startet die bessere Texterkennung nicht. -> Kostenlos installieren: "Microsoft Visual C++ Redistributable x64" herunterladen (Link: aka.ms/vs/17/release/vc_redist.x64.exe), doppelklicken, danach diesen PC-Check wiederholen.' '). Without it the better text recognition cannot start. -> Install it for free: download "Microsoft Visual C++ Redistributable x64" (link: aka.ms/vs/17/release/vc_redist.x64.exe), double-click it, then run this PC-Check again.'))
    }
} catch { Zeile 'INFO' (T 'Visual C++ Laufzeit nicht pruefbar.' 'Could not check the Visual C++ runtime.') }

# ---- 10. Virenschutz und Firewall --------------------------------------------------------------
Kopf (T '9. Virenschutz und Firewall' '9. Antivirus and firewall')
$avNamen = @()
$defenderAktiv = $false
try {
    $produkte = @(Get-CimInstance -Namespace 'root\SecurityCenter2' -ClassName AntiVirusProduct -ErrorAction Stop)
    foreach ($p in $produkte) {
        $name = [string]$p.displayName
        $an = (([int]$p.productState -band 0x1000) -ne 0)
        $avNamen += $name
        if ($name -match 'Defender' -and $an) { $defenderAktiv = $true }
        $zustand = if ($an) { T ' (aktiv)' ' (active)' } else { T ' (aus)' ' (off)' }
        Zeile 'INFO' ((T 'Virenprogramm: ' 'Antivirus: ') + $name + $zustand)
    }
    if ($produkte.Count -eq 0) { Zeile 'INFO' (T 'Kein Virenprogramm gemeldet.' 'No antivirus program reported.') }
} catch {
    Zeile 'INFO' (T 'Virenprogramm-Liste nicht lesbar (Windows-Sicherheitscenter nicht erreichbar).' 'Could not read the antivirus list (Windows Security Center not reachable).')
    $bekannt = [ordered]@{ 'Norton' = 'C:\Program Files\Norton Security'; 'Norton 360' = 'C:\Program Files\Norton 360'; 'McAfee' = 'C:\Program Files\McAfee'; 'Avast' = 'C:\Program Files\AVAST Software'; 'AVG' = 'C:\Program Files\AVG'; 'Bitdefender' = 'C:\Program Files\Bitdefender'; 'Kaspersky' = 'C:\Program Files (x86)\Kaspersky Lab' }
    foreach ($k in $bekannt.Keys) {
        if (Test-Path -LiteralPath $bekannt[$k]) { $avNamen += $k; Zeile 'INFO' ((T 'Virenprogramm gefunden: ' 'Antivirus found: ') + $k) }
    }
}
$fremdAV = @($avNamen | Where-Object { $_ -notmatch 'Defender' })

# Klickwege je Virenprogramm -- am Rechner wird nichts veraendert
$ausnahmeOrdner = if ($botOrdner) { $botOrdner } else { 'C:\HustleBot' }
function Anleitung($name) {
    switch -Regex ($name) {
        'Norton' { return (T 'Norton: Norton oeffnen -> Einstellungen -> Antivirus -> Scans und Risiken -> "Von Scans auszuschliessende Elemente" -> Konfigurieren -> Ordner hinzufuegen: ' 'Norton: open Norton -> Settings -> Antivirus -> Scans and Risks -> "Items to Exclude from Scans" -> Configure -> Add Folders: ') + $ausnahmeOrdner + (T ' (ebenso unter "Auto-Protect, SONAR und Download Intelligence"). Wurde etwas isoliert: Norton -> Sicherheitsverlauf -> Quarantaene -> Datei wiederherstellen und ausschliessen.' ' (also under "Items to Exclude from Auto-Protect, SONAR and Download Intelligence Detection"). If something was quarantined: Norton -> Security History -> Quarantine -> Restore and exclude the file.') }
        'McAfee' { return (T 'McAfee: McAfee oeffnen -> Mein Schutz -> Echtzeit-Scan -> Ausgeschlossene Dateien -> Datei hinzufuegen: ' 'McAfee: open McAfee -> My Protection -> Real-Time Scanning -> Excluded Files -> Add file: ') + $ausnahmeOrdner }
        'Avast' { return (T 'Avast: Avast oeffnen -> Menue -> Einstellungen -> Allgemein -> Ausnahmen -> Ausnahme hinzufuegen: ' 'Avast: open Avast -> Menu -> Settings -> General -> Exceptions -> Add Exception: ') + $ausnahmeOrdner + (T ' (bei Quarantaene: Schutz -> Virentruhe -> Wiederherstellen und Ausnahme).' ' (if quarantined: Protection -> Virus Chest -> Restore and add exception).') }
        'AVG' { return (T 'AVG: AVG oeffnen -> Menue -> Einstellungen -> Allgemein -> Ausnahmen -> Ausnahme hinzufuegen: ' 'AVG: open AVG -> Menu -> Settings -> General -> Exceptions -> Add Exception: ') + $ausnahmeOrdner }
        'Bitdefender' { return (T 'Bitdefender: Schutz -> Antivirus -> Einstellungen -> Ausnahmen verwalten -> Ausnahme hinzufuegen: ' 'Bitdefender: Protection -> Antivirus -> Settings -> Manage Exceptions -> Add an Exception: ') + $ausnahmeOrdner }
        'Kaspersky' { return (T 'Kaspersky: Einstellungen -> Bedrohungen und Ausschluesse -> Ausschluesse verwalten -> Hinzufuegen: ' 'Kaspersky: Settings -> Threats and Exclusions -> Manage Exclusions -> Add: ') + $ausnahmeOrdner }
        default { return (T 'Im Virenprogramm eine Ausnahme (Ausschluss) fuer den Ordner anlegen: ' 'In your antivirus add an exception (exclusion) for the folder: ') + $ausnahmeOrdner }
    }
}
foreach ($n in $fremdAV) {
    Zeile 'WARN' ((T 'Fremdes Virenprogramm "' 'Third-party antivirus "') + $n + (T '" kann den Bot oder das Spiel blockieren (der Bot steuert die Maus, das wirkt auf manche Programme verdaechtig). -> ' '" can block the bot or the game (the bot controls the mouse, which some programs find suspicious). -> ') + (Anleitung $n))
}

# Windows Defender: Ausnahmen, Ordnerschutz, Funde (ohne Administrator nur teilweise lesbar)
try {
    $mp = Get-MpPreference -ErrorAction Stop
    if ($mp.EnableControlledFolderAccess -eq 1) {
        Zeile 'WARN' (T 'Defender "Kontrollierter Ordnerzugriff" ist AN. Er kann Schreibzugriffe des Bots blockieren. -> Windows-Sicherheit -> Viren- & Bedrohungsschutz -> Ransomware-Schutz -> App ueber den Kontrollierten Ordnerzugriff zulassen -> HustleBot.exe und runtime\bin\javaw.exe hinzufuegen.' 'Defender "Controlled folder access" is ON. It can block the bot from writing files. -> Windows Security -> Virus & threat protection -> Ransomware protection -> Allow an app through Controlled folder access -> add HustleBot.exe and runtime\bin\javaw.exe.')
    }
    $ausn = @($mp.ExclusionPath | Where-Object { $_ -and $_ -notmatch 'N/A' })
    if ($botOrdner -and $ausn.Count -gt 0) {
        $drin = @($ausn | Where-Object { $botOrdner -like ($_.TrimEnd('\') + '*') })
        if ($drin.Count -gt 0) { Zeile 'OK' (T 'Defender: Der Bot-Ordner ist als Ausnahme eingetragen.' 'Defender: the bot folder is listed as an exclusion.') }
    }
} catch {
    if ($defenderAktiv) { Zeile 'INFO' (T 'Defender-Einstellungen ohne Administrator nicht lesbar -- kein Problem, nur nicht pruefbar.' 'Defender settings cannot be read without administrator rights -- not a problem, just not checkable.') }
}
$defenderFundGelesen = $false
try {
    $funde = @(Get-MpThreatDetection -ErrorAction Stop | Where-Object { ($_.Resources -join ' ') -match 'HustleBot|hustle-bot|Hustle Castle' })
    $defenderFundGelesen = $true
    if ($funde.Count -gt 0) {
        $erste = (($funde | Select-Object -First 1).Resources -join ', ')
        Zeile 'FEHLER' ((T 'Defender hat etwas vom Bot gemeldet oder isoliert: ' 'Defender reported or quarantined something from the bot: ') + $erste + (T '. -> Windows-Sicherheit -> Viren- & Bedrohungsschutz -> Schutzverlauf -> Eintrag oeffnen -> Aktionen -> "Zulassen" bzw. "Wiederherstellen"; danach einen Ausschluss fuer den Bot-Ordner anlegen (Viren- & Bedrohungsschutz -> Einstellungen verwalten -> Ausschluesse -> Ausschluss hinzufuegen -> Ordner). Dann den Bot neu auspacken.' '. -> Windows Security -> Virus & threat protection -> Protection history -> open the entry -> Actions -> "Allow" or "Restore"; then add an exclusion for the bot folder (Virus & threat protection -> Manage settings -> Exclusions -> Add an exclusion -> Folder). Then unpack the bot again.'))
    } elseif ($defenderAktiv) { Zeile 'OK' (T 'Defender: nichts vom Bot gemeldet oder isoliert.' 'Defender: nothing from the bot reported or quarantined.') }
} catch { }
if ($defenderAktiv -and -not $defenderFundGelesen) {
    Zeile 'INFO' (T 'Defender-Funde ohne Administrator nicht lesbar. Pruefen Sie von Hand: Windows-Sicherheit -> Viren- & Bedrohungsschutz -> Schutzverlauf -- steht dort HustleBot, waehlen Sie "Zulassen".' 'Defender history cannot be read without administrator rights. Check by hand: Windows Security -> Virus & threat protection -> Protection history -- if HustleBot is listed, choose "Allow".')
}
try {
    $ev = @(Get-WinEvent -FilterHashtable @{ LogName = 'Microsoft-Windows-Windows Defender/Operational'; Id = 1116, 1117, 1121, 1123, 1124; StartTime = (Get-Date).AddDays(-30) } -MaxEvents 200 -ErrorAction Stop | Where-Object { $_.Message -match 'HustleBot|hustle-bot|javaw' })
    if ($ev.Count -gt 0) { Zeile 'WARN' ((T 'Defender-Ereignisse der letzten 30 Tage nennen den Bot: ' 'Defender events in the last 30 days mention the bot: ') + $ev.Count + (T ' -- siehe Schutzverlauf (Windows-Sicherheit), dort "Zulassen" waehlen und einen Ausschluss anlegen.' ' -- see Protection history (Windows Security), choose "Allow" and add an exclusion.')) }
} catch { }

# Programmdateien: "aus dem Internet"-Sperre, fehlende Dateien (Quarantaene), ungeeigneter Ordner
if ($botOrdner) {
    $blockiert = @()
    foreach ($d in 'HustleBot.exe', 'Settings.exe', 'Capture-Images.exe', 'app\hustle-bot.jar') {
        $pfad = Join-Path $botOrdner $d
        if (Test-Path -LiteralPath $pfad) {
            try { if (Get-Item -LiteralPath $pfad -Stream Zone.Identifier -ErrorAction Stop) { $blockiert += $d } } catch { }
        }
    }
    if ($blockiert.Count -gt 0) { Zeile 'WARN' ((T 'Windows hat diese Dateien als "aus dem Internet" markiert: ' 'Windows marked these files as "from the internet": ') + ($blockiert -join ', ') + (T '. -> Rechtsklick auf die Datei -> Eigenschaften -> unten "Zulassen" ankreuzen -> OK.' '. -> Right-click the file -> Properties -> tick "Unblock" at the bottom -> OK.')) }
    else { Zeile 'OK' (T 'Keine "aus dem Internet"-Sperre auf den Programmdateien.' 'No "from the internet" block on the program files.') }
    $weg = @('HustleBot.exe', 'Settings.exe', 'runtime\bin\javaw.exe', 'app\hustle-bot.jar') | Where-Object { -not (Test-Path -LiteralPath (Join-Path $botOrdner $_)) }
    if ($weg.Count -gt 0) { Zeile 'FEHLER' ((T 'Es fehlen Dateien im Bot-Ordner: ' 'Files are missing in the bot folder: ') + ($weg -join ', ') + (T ' -- oft hat ein Virenprogramm sie in Quarantaene verschoben. Im Virenprogramm unter Quarantaene/Virentruhe nachsehen, wiederherstellen, einen Ausschluss fuer den Bot-Ordner anlegen und neu auspacken.' ' -- often an antivirus moved them to quarantine. Look in the antivirus quarantine/virus chest, restore them, add an exclusion for the bot folder and unpack again.')) }
    if ($botOrdner -match 'OneDrive|Program Files') { Zeile 'WARN' ((T 'Der Bot-Ordner liegt unter ' 'The bot folder is under ') + $botOrdner + (T ' -- besser C:\HustleBot (OneDrive/Programme blockieren Schreibzugriffe).' ' -- better use C:\HustleBot (OneDrive/Program Files can block writing).')) }
} else {
    Zeile 'INFO' (T 'Bot noch nicht installiert (C:\HustleBot fehlt) -- die Datei-Pruefungen kommen nach dem Setup (PC-Check dann noch einmal starten).' 'Bot not installed yet (C:\HustleBot missing) -- the file checks run after setup (run this PC-Check again then).')
}

# Windows-Firewall: Sperr-Regeln gegen den Bot (ohne Administrator lesbar)
try {
    $filter = @(Get-NetFirewallApplicationFilter -ErrorAction Stop | Where-Object { $_.Program -and ($_.Program -match 'HustleBot|hustle-bot' -or ($botOrdner -and $_.Program -like ($botOrdner + '\*'))) })
    $blockRegeln = @()
    $erlaubRegeln = 0
    foreach ($f in $filter) {
        $r = $f | Get-NetFirewallRule -ErrorAction SilentlyContinue
        if ($r -and $r.Enabled -eq 'True') {
            if ($r.Action -eq 'Block') { $blockRegeln += ($r.DisplayName + ' (' + $r.Direction + ')') } else { $erlaubRegeln++ }
        }
    }
    if ($blockRegeln.Count -gt 0) { Zeile 'FEHLER' ((T 'Die Windows-Firewall hat eine BLOCK-Regel fuer den Bot: ' 'The Windows firewall has a BLOCK rule for the bot: ') + ($blockRegeln -join '; ') + (T '. -> Windows-Sicherheit -> Firewall & Netzwerkschutz -> Erweiterte Einstellungen -> Ausgehende/Eingehende Regeln -> Regel suchen -> Rechtsklick -> Deaktivieren oder Loeschen.' '. -> Windows Security -> Firewall & network protection -> Advanced settings -> Outbound/Inbound rules -> find the rule -> right-click -> Disable or Delete.')) }
    elseif ($erlaubRegeln -gt 0) { Zeile 'OK' (T 'Firewall: Es gibt Zulassen-Regeln fuer den Bot, keine Sperre.' 'Firewall: there are allow rules for the bot, no block rule.') }
    else { Zeile 'OK' (T 'Firewall: keine Regel gegen den Bot (ohne Discord braucht er kein Internet).' 'Firewall: no rule against the bot (without Discord it needs no internet).') }
} catch { Zeile 'INFO' (T 'Firewall-Regeln nicht lesbar.' 'Could not read the firewall rules.') }
if ($fremdAV.Count -gt 0) { Zeile 'INFO' (T 'Die Firewall und Sperren Ihres Virenprogramms sind hier nicht lesbar -- die Ausnahme (siehe oben) gilt meist auch fuer dessen Firewall.' 'The firewall and blocks of your antivirus cannot be read here -- the exception (see above) usually covers its firewall as well.') }

# ---- 8. Der Bot selbst (nur wenn der Check im Bot-Ordner liegt) --------------------------
if (Test-Path -LiteralPath (Join-Path $hier 'app\hustle-bot.jar')) {
    Kopf (T '10. Bot-Ordner' '10. Bot folder')
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

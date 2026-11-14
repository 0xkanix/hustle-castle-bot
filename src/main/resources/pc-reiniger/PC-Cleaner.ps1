# =====================================================================
#  PC-Cleaner für den Hustle Castle Bot (Autor, 07.10.2026)
#  Räumt Datenmüll auf, prüft Programme, mistet den Autostart aus, schaltet
#  Hintergrund-Bremsen ab und stellt Windows auf Leistung. Fragt vor JEDEM
#  Schritt. Persönliche Dateien werden nie angefasst.
#  Mit Fensteroberfläche (Autor: "dass es nicht so aussieht, als würde der PC
#  gehackt werden"). Start: PC-Cleaner.bat. Test ohne Änderungen: -Probe
# =====================================================================
param([switch]$Probe, [string]$Sprache = "")

$ErrorActionPreference = "SilentlyContinue"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
# Versteckt gestartet (PC-Cleaner.bat), nimmt Windows das ERSTE Anzeigen eines Fensters als
# "versteckt". Deshalb zuerst das (unsichtbare) Konsolenfenster verstecken -- das verbraucht
# diese Regel, und alle Fenster danach erscheinen normal.
Add-Type -Namespace PR0 -Name K -MemberDefinition '[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow(); [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);'
[PR0.K]::ShowWindow([PR0.K]::GetConsoleWindow(), 0) | Out-Null

$script:de = $true
$bericht = New-Object System.Collections.Generic.List[string]
function T($d, $e) { if ($script:de) { $d } else { $e } }

# --- Aussehen -----------------------------------------------------------
$cBlau  = [Drawing.Color]::FromArgb(93, 64, 55)      # Dunkelbraun (Kopf, Titel, Hauptknopf)
$cGruen = [Drawing.Color]::FromArgb(85, 107, 47)     # Olivgruen (erledigt)
$cGrau  = [Drawing.Color]::FromArgb(141, 123, 112)   # Graubraun
$cText  = [Drawing.Color]::FromArgb(62, 39, 35)      # Text dunkelbraun
$cHell  = [Drawing.Color]::FromArgb(245, 239, 230)   # Creme (Hintergrund)
$cOrange = [Drawing.Color]::FromArgb(176, 122, 74)   # Karamell
$cRot   = [Drawing.Color]::FromArgb(160, 82, 45)     # Sienna
$cPapier = [Drawing.Color]::FromArgb(255, 251, 245)  # Papierweiss (Verlauf, Fragen)
$cTan   = [Drawing.Color]::FromArgb(222, 205, 185)   # Hellbraun (Balken-Grund)
$fNorm  = New-Object Drawing.Font("Segoe UI", 10)
$fFett  = New-Object Drawing.Font("Segoe UI Semibold", 11)
$fTitel = New-Object Drawing.Font("Segoe UI Semibold", 20)
$script:haupt = $null

function Ui { [System.Windows.Forms.Application]::DoEvents() }
function Warte($ms) { $bis = (Get-Date).AddMilliseconds($ms); while ((Get-Date) -lt $bis) { Start-Sleep -Milliseconds 50; Ui }; if (Get-Command Fortschritt -ErrorAction SilentlyContinue) { Fortschritt } }

# Ein Fenster mit Frage und Knöpfen. Gibt die Nummer des gedrückten Knopfs zurück.
function Dialog($titel, $text, [string[]]$knoepfe, $farben) {
    $f = New-Object Windows.Forms.Form
    $zeilen = ($text -split "`n").Count + [math]::Floor($text.Length / 75); $hoehe = [math]::Max(110, [math]::Min(520, $zeilen * 21))
    $f.Text = "PC-Cleaner"; $f.ClientSize = New-Object Drawing.Size(540, (140 + $hoehe)); $f.BackColor = $cPapier
    $f.FormBorderStyle = "FixedDialog"; $f.MaximizeBox = $false; $f.MinimizeBox = $false; $f.TopMost = $true
    $f.StartPosition = if ($script:haupt) { "CenterParent" } else { "CenterScreen" }
    $l1 = New-Object Windows.Forms.Label; $l1.UseMnemonic = $false; $l1.Text = $titel; $l1.Font = $fFett; $l1.ForeColor = $cBlau
    $l1.Location = New-Object Drawing.Point(24, 20); $l1.Size = New-Object Drawing.Size(490, 28)
    $l2 = New-Object Windows.Forms.Label; $l2.UseMnemonic = $false; $l2.Text = $text; $l2.Font = $fNorm; $l2.ForeColor = $cText
    $l2.Location = New-Object Drawing.Point(24, 56); $l2.Size = New-Object Drawing.Size(490, $hoehe)
    $f.Controls.AddRange(@($l1, $l2))
    $breite = 150; $x = 540 - 24 - ($knoepfe.Count * ($breite + 10)) + 10
    for ($i = 0; $i -lt $knoepfe.Count; $i++) {
        $b = New-Object Windows.Forms.Button; $b.Text = $knoepfe[$i]; $b.Font = $fNorm
        $b.Size = New-Object Drawing.Size($breite, 38); $b.Location = New-Object Drawing.Point(($x + $i * ($breite + 10)), (80 + $hoehe))
        $b.FlatStyle = "Flat"; $b.FlatAppearance.BorderSize = 0; $b.BackColor = $farben[$i]; $b.ForeColor = [Drawing.Color]::White
        $b.Tag = $i; $b.Add_Click({ $this.FindForm().Tag = $this.Tag; $this.FindForm().Close() })
        $f.Controls.Add($b)
    }
    $f.Tag = $knoepfe.Count - 1
    $frageAb = Get-Date
    if ($script:haupt) { $f.ShowDialog($script:haupt) | Out-Null } else { $f.ShowDialog() | Out-Null }
    $script:fragZeit += ((Get-Date) - $frageAb).TotalSeconds
    return [int]$f.Tag
}

# --- Hauptfenster -------------------------------------------------------
function Hauptfenster {
    $h = New-Object Windows.Forms.Form
    $h.Text = "PC-Cleaner"; $h.ClientSize = New-Object Drawing.Size(820, 560); $h.BackColor = $cHell
    $h.FormBorderStyle = "FixedSingle"; $h.MaximizeBox = $false; $h.StartPosition = "CenterScreen"
    $kopf = New-Object Windows.Forms.Panel; $kopf.Dock = "Top"; $kopf.Height = 86; $kopf.BackColor = $cBlau
    $t1 = New-Object Windows.Forms.Label; $t1.Text = "PC-Cleaner"; $t1.Font = $fTitel; $t1.ForeColor = [Drawing.Color]::White
    $t1.Location = New-Object Drawing.Point(24, 10); $t1.AutoSize = $true
    $t2 = New-Object Windows.Forms.Label; $t2.Font = $fNorm; $t2.ForeColor = [Drawing.Color]::FromArgb(239, 222, 205)
    $t2.Text = (T "Für einen schnelleren PC und Bot  ·  vor jedem Schritt wirst du gefragt" "For a faster PC and bot  ·  you are asked before every step")
    if ($Probe) { $t2.Text += (T "  ·  TESTMODUS (ändert nichts)" "  ·  TEST MODE (changes nothing)") }
    $t2.Location = New-Object Drawing.Point(27, 54); $t2.AutoSize = $true
    $kopf.Controls.AddRange(@($t1, $t2)); $h.Controls.Add($kopf)

    $script:schrittLabels = @()
    $namen = @((T "Diagnose" "Diagnosis"), (T "Sicherheitsnetz" "Safety net"), (T "Programme prüfen" "Check programs"),
               (T "Datenmüll" "Junk files"), (T "Browser & Apps" "Browsers & apps"), (T "Windows-Reste" "Windows leftovers"),
               "Autostart", (T "Hintergrund-Bremsen" "Background brakes"), (T "Leistung" "Performance"),
               (T "Festplatte" "Drive"), (T "Gesundheit & Viren" "Health & viruses"), (T "Bericht" "Report"))
    for ($i = 0; $i -lt $namen.Count; $i++) {
        $l = New-Object Windows.Forms.Label; $l.UseMnemonic = $false; $l.Text = "○   " + $namen[$i]; $l.Font = $fNorm; $l.ForeColor = $cGrau
        $l.Location = New-Object Drawing.Point(24, (106 + $i * 32)); $l.Size = New-Object Drawing.Size(210, 28)
        $h.Controls.Add($l); $script:schrittLabels += $l
    }
    $script:log = New-Object Windows.Forms.RichTextBox
    $script:log.Location = New-Object Drawing.Point(240, 104); $script:log.Size = New-Object Drawing.Size(556, 386)
    $script:log.ReadOnly = $true; $script:log.BorderStyle = "None"; $script:log.BackColor = $cPapier
    $script:log.Font = $fNorm; $h.Controls.Add($script:log)
    $script:balkenGrund = New-Object Windows.Forms.Panel; $script:balkenGrund.BackColor = $cTan
    $script:balkenGrund.Location = New-Object Drawing.Point(24, 512); $script:balkenGrund.Size = New-Object Drawing.Size(772, 14)
    $script:balken = New-Object Windows.Forms.Panel; $script:balken.BackColor = $cBlau
    $script:balken.Location = New-Object Drawing.Point(0, 0); $script:balken.Size = New-Object Drawing.Size(0, 14)
    $script:balkenGrund.Controls.Add($script:balken); $h.Controls.Add($script:balkenGrund)
    $script:prozent = New-Object Windows.Forms.Label; $script:prozent.Font = $fFett; $script:prozent.ForeColor = $cBlau
    $script:prozent.Location = New-Object Drawing.Point(22, 491); $script:prozent.Size = New-Object Drawing.Size(300, 20); $script:prozent.Text = "0 %"
    $script:rest = New-Object Windows.Forms.Label; $script:rest.Font = $fNorm; $script:rest.ForeColor = $cGrau; $script:rest.TextAlign = "MiddleRight"
    $script:rest.Location = New-Object Drawing.Point(446, 491); $script:rest.Size = New-Object Drawing.Size(350, 20)
    $h.Controls.AddRange(@($script:prozent, $script:rest))
    $h.Add_FormClosing({ if (-not $script:fertig) { [Environment]::Exit(0) } })
    $h.Show(); $h.Visible = $true; $h.Activate(); Ui
    return $h
}

$script:protokoll = Join-Path $env:TEMP "PC-Cleaner-Protokoll.txt"
function Log($text, $farbe, [switch]$fett) {
    if ($Probe) { Write-Host $text }
    Add-Content -Path $script:protokoll -Value ((Get-Date -Format 'HH:mm:ss') + '  ' + $text) -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $script:log) { return }
    $script:log.SelectionStart = $script:log.TextLength
    $script:log.SelectionFont = if ($fett) { $fFett } else { $fNorm }
    $script:log.SelectionColor = if ($farbe) { $farbe } else { $cText }
    $script:log.AppendText($text + "`n"); $script:log.ScrollToCaret(); Ui
}
function Titel($nr, $d, $e) {
    for ($i = 0; $i -lt $script:schrittLabels.Count; $i++) {
        $l = $script:schrittLabels[$i]; $name = $l.Text.Substring(4)
        if ($i -lt $nr - 1) { $l.Text = "✔   " + $name; $l.ForeColor = $cGruen; $l.Font = $fNorm }
        elseif ($i -eq $nr - 1) { $l.Text = "▶   " + $name; $l.ForeColor = $cBlau; $l.Font = $fFett }
    }
    Balken ([math]::Min(12, $nr - 1))
    Log "" ; Log (T $d $e) $cBlau -fett
}
# Fortschritt in Prozent und geschaetzte Restzeit (Autor, 07.10.). Typische Dauer je Schritt in
# Sekunden; laeuft ein Schritt laenger, bleibt er bei 95 % seines Anteils stehen. Die Zeit, in der
# eine Frage auf eine Antwort wartet, zaehlt nicht mit.
$script:dauer = @{ 1 = 4; 2 = 45; 3 = 12; 4 = 12; 5 = 10; 6 = 300; 7 = 6; 8 = 4; 9 = 4; 10 = 30; 11 = 180; 12 = 3 }
$script:schritt = 0; $script:schrittStart = Get-Date; $script:fragZeit = 0
function Fortschritt {
    if (-not $script:prozent) { return }
    $gesamt = ($script:dauer.Values | Measure-Object -Sum).Sum
    $fertig = 0; foreach ($k in $script:dauer.Keys) { if ($k -lt $script:schritt) { $fertig += $script:dauer[$k] } }
    if ($script:schritt -ge 1 -and $script:schritt -le 12) {
        $lauf = ((Get-Date) - $script:schrittStart).TotalSeconds - $script:fragZeit
        $fertig += [math]::Min([double]($script:dauer[$script:schritt] * 0.95), [math]::Max([double]0.0, [double]$lauf))
    }
    if ($script:schritt -gt 12) { $fertig = $gesamt }
    $anteil = [math]::Min([double]1.0, [double]($fertig / $gesamt))   # 1.0, nicht 1: sonst rundet PowerShell auf 0 ab
    $script:balken.Width = [int](772 * $anteil)
    $script:prozent.Text = ([math]::Floor($anteil * 100)).ToString() + " %"
    $s = [int][math]::Max(0, $gesamt - $fertig)
    $script:rest.Text = if ($anteil -ge 1) { (T "fertig" "done") } elseif ($s -ge 60) { (T "noch ca. " "about ") + [math]::Floor($s / 60) + " min " + ($s % 60) + " s" + (T "" " left") } else { (T "noch ca. " "about ") + $s + " s" + (T "" " left") }
}
function Balken($n) { $script:schritt = $n + 1; if ($n -ge 12) { $script:schritt = 13 }; $script:schrittStart = Get-Date; $script:fragZeit = 0; Fortschritt; Ui }
function Info($d, $e) { Log ("   " + (T $d $e)) $cGrau }
function Notiz($t) { $bericht.Add($t); Log ("   ✔  " + $t) $cGruen }
function Frage($d, $e) {
    if ($Probe) { Log ("   [Test] " + (T $d $e) + (T "  → Ja (nur simuliert)" "  → Yes (simulated)")) $cOrange; return $true }
    return ((Dialog (T "Kurze Frage" "Quick question") (T $d $e) @((T "Ja" "Yes"), (T "Nein" "No")) @($cBlau, $cGrau)) -eq 0)
}
function Groesse($pfade) {
    $s = 0
    foreach ($p in $pfade) { if ((Sicher $p) -and (Test-Path $p)) { Get-ChildItem $p -Recurse -Force -File -ErrorAction SilentlyContinue | ForEach-Object { $s += $_.Length }; Ui } }
    return $s
}
function MB($b) { "{0:N0} MB" -f ($b / 1MB) }
function FreiC { [math]::Round((Get-PSDrive C).Free / 1GB, 1) }

# --- Sicherheitsgurte (Autor, 07.10.: "auf keinen Fall irgendwelche wichtigen Sachen loeschen oder
#     Windows-Dateien wie System32") ---------------------------------------------------------------
# Geloescht wird NUR in Ordnern, die ausdruecklich Temp, Zwischenspeicher oder Absturzberichte sind,
# nie in Windows-Systemordnern, nie in Programm- oder Benutzer-Hauptordnern, nie bei leeren Pfaden.
$script:verboten = @($env:WINDIR, "$env:WINDIR\System32", "$env:WINDIR\SysWOW64", "$env:WINDIR\WinSxS", $env:ProgramFiles,
    ${env:ProgramFiles(x86)}, $env:ProgramData, $env:LOCALAPPDATA, $env:APPDATA, "$env:LOCALAPPDATA\Programs", $env:USERPROFILE,
    [Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('MyDocuments'), [Environment]::GetFolderPath('MyPictures'),
    [Environment]::GetFolderPath('MyMusic'), [Environment]::GetFolderPath('MyVideos'), "$env:SystemDrive\Users", "$env:SystemDrive\") |
    Where-Object { $_ } | ForEach-Object { $_.TrimEnd('\').ToLower() }
$script:systemTabu = @("$env:WINDIR\System32", "$env:WINDIR\SysWOW64", "$env:WINDIR\WinSxS", "$env:WINDIR\servicing",
    "$env:WINDIR\Boot", "$env:WINDIR\assembly", "$env:WINDIR\Fonts", "$env:WINDIR\Installer") | Where-Object { $_ -and $env:WINDIR } | ForEach-Object { $_.ToLower() }
function Basis($muster) { ($muster -replace '\\[^\\]*[\*\?][^\\]*$', '').TrimEnd('\') }
function Sicher($muster) {
    $b = Basis $muster
    try { if (-not $b -or -not [IO.Path]::IsPathRooted($b)) { return $false } } catch { return $false }
    if (($b -split '\\').Count -lt 3) { return $false }
    $kl = $b.ToLower()
    if ($script:verboten -contains $kl) { return $false }
    foreach ($s in $script:systemTabu) { if ($kl.StartsWith($s)) { return $false } }
    return ($kl -match '\\(temp|tmp|cache|cache2|code cache|gpucache|htmlcache|httpcache|webcache|cachestorage|dxcache|glcache|dxccache|d3dscache|storage|minidump|crashdumps|inetcache|explorer|download|reportarchive|reportqueue)$')
}
function Wegraeumen($muster) { foreach ($p in $muster) { if (Sicher $p) { Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue }; Ui } }
# Ein Programmordner darf fuer "Deaktivieren" nur ein eigener Unterordner sein -- nie Windows,
# nie "Program Files" selbst, nie ein Benutzer-Hauptordner.
function OrdnerOk($o) {
    try { if (-not $o -or -not [IO.Path]::IsPathRooted($o)) { return $false } } catch { return $false }
    $kl = $o.TrimEnd('\').ToLower()
    if ($kl -match '^[a-z]:$') { return $false }
    if ($script:verboten -contains $kl) { return $false }
    if ($env:WINDIR -and $kl.StartsWith($env:WINDIR.ToLower())) { return $false }
    return $true
}
# Liegt ein Pfad IM Ordner (oder ist er der Ordner selbst)? Kein blosser Namensanfang:
# "C:\Programme\Foo" trifft nicht "C:\Programme\Foobar\x.exe" (Sicherheits-Update 16.10.).
function ImOrdner($pfad, $ordner) {
    if (-not $pfad -or -not $ordner) { return $false }
    try {
        $p = [IO.Path]::GetFullPath(([string]$pfad).Trim().TrimEnd('\'))
        $o = [IO.Path]::GetFullPath(([string]$ordner).Trim().TrimEnd('\'))
    } catch { return $false }
    if ($p -eq $o) { return $true }
    return $p.StartsWith($o + '\', [StringComparison]::OrdinalIgnoreCase)
}
# Die ausfuehrbare Datei aus einer Befehlszeile: "C:\x\a.exe" /S -> C:\x\a.exe. Ohne
# Anfuehrungszeichen: der kuerzeste Anfang, der auf .exe endet und als Datei da ist.
function ExeAus($befehl) {
    if (-not $befehl) { return $null }
    $b = [Environment]::ExpandEnvironmentVariables(([string]$befehl).Trim())
    if ($b.StartsWith('"')) {
        $e = $b.IndexOf('"', 1)
        if ($e -gt 1) { return $b.Substring(1, $e - 1) } else { return $b.Trim('"') }
    }
    $treffer = [regex]::Matches($b, '(?i)\.exe(?=\s|$)')
    foreach ($t in $treffer) {
        $k = $b.Substring(0, $t.Index + 4)
        if (Test-Path -LiteralPath $k -PathType Leaf) { return $k }
    }
    if ($treffer.Count -gt 0) { return $b.Substring(0, $treffer[0].Index + 4) }
    return ($b -split '\s+')[0]
}
# Der Rest der Befehlszeile hinter der ausfuehrbaren Datei (ihre Parameter).
function RestNach($befehl, $exe) {
    $b = [Environment]::ExpandEnvironmentVariables(([string]$befehl).Trim())
    if ($b.StartsWith('"')) {
        $e = $b.IndexOf('"', 1)
        if ($e -gt 1) { return $b.Substring($e + 1).Trim() } else { return '' }
    }
    if ($exe -and $b.Length -gt $exe.Length) { return $b.Substring($exe.Length).Trim() }
    return ''
}
# Etwas Langes im Hintergrund laufen lassen, ohne dass das Fenster einfriert.
# Mit Hoechstzeit (Autor, 07.10.: "es ist nicht weiter gelaufen bei Windows-Reste"): dauert es laenger,
# wird abgebrochen und es geht weiter. Liefert $true, wenn die Arbeit fertig wurde.
function ImHintergrund([scriptblock]$arbeit, [int]$maxSek = 0) {
    $job = Start-Job -ScriptBlock $arbeit
    $ab = Get-Date
    while ($job.State -eq "Running") {
        Warte 250
        if ($maxSek -gt 0 -and ((Get-Date) - $ab).TotalSeconds -gt $maxSek) {
            Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
            Log ("   ⚠  " + (T "Dauert zu lange -- abgebrochen, es geht weiter (Windows holt das spaeter selbst nach)." "Takes too long -- stopped, continuing (Windows will do it later by itself).")) $cOrange
            return $false
        }
    }
    Receive-Job $job -ErrorAction SilentlyContinue | Out-Null; Remove-Job $job -Force
    return $true
}

# --- Sicherheitsnetz (07.10.: ein einziger kaputter Windows-Eintrag liess den Reiniger abstuerzen) ---
trap {
    if ($script:log) { Log ("   ⚠  " + (T "Unerwarteter Fehler, wird uebersprungen: " "Unexpected error, skipped: ") + $_.Exception.Message) $cOrange }
    Add-Content -Path (Join-Path $env:TEMP "PC-Cleaner-Protokoll.txt") -Value ('FEHLER Zeile ' + $_.InvocationInfo.ScriptLineNumber + ': ' + $_.Exception.Message) -Encoding UTF8 -ErrorAction SilentlyContinue
    continue
}

# --- Start --------------------------------------------------------------
Set-Content -Path (Join-Path $env:TEMP "PC-Cleaner-Protokoll.txt") -Value ((Get-Date -Format 'dd.MM.yyyy HH:mm:ss') + '  PC-Cleaner gestartet (Admin: ' + ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) + ')') -Encoding UTF8 -ErrorAction SilentlyContinue
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($Sprache -eq "en") { $script:de = $false }
elseif ($Sprache -ne "de" -and -not $Probe) {
    $s = Dialog "PC-Cleaner" "Sprache wählen  /  Choose your language" @("Deutsch", "English") @($cBlau, $cBlau)
    if ($s -eq 1) { $script:de = $false }
}
if (-not $admin -and -not $Probe) {
    Dialog "PC-Cleaner" (T "Bitte mit PC-Cleaner.bat starten -- es braucht Admin-Rechte." "Please start with PC-Cleaner.bat -- admin rights are needed.") @("OK") @($cBlau) | Out-Null
    exit
}
$script:haupt = Hauptfenster
$freiVorher = FreiC

# --- 1. Diagnose ----------------------------------------------------------
Titel 1 "Diagnose: Was steckt in diesem PC?" "Diagnosis: what is in this PC?"
$cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1)
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$os = (Get-CimInstance Win32_OperatingSystem)
$sysDisk = Get-Partition -DriveLetter C | Get-Disk
$phys = Get-PhysicalDisk | Where-Object { $_.DeviceId -eq $sysDisk.Number } | Select-Object -First 1
$art = if ($phys.MediaType -eq "HDD") { "HDD" } elseif ($phys.MediaType -eq "SSD") { "SSD" } else { [string]$phys.MediaType }
Info ("Prozessor: " + $cpu.Name.Trim() + " (" + $cpu.NumberOfCores + " Kerne)") ("Processor: " + $cpu.Name.Trim() + " (" + $cpu.NumberOfCores + " cores)")
Info "Arbeitsspeicher: $ramGB GB" "Memory: $ramGB GB"
Info "Laufwerk C: $art, frei $freiVorher GB" "Drive C: $art, free $freiVorher GB"
Info ("Windows: " + $os.Caption) ("Windows: " + $os.Caption)
if ($art -eq "HDD") { $script:dauer[10] = 900 }
$bericht.Add("CPU: " + $cpu.Name.Trim() + " | RAM: $ramGB GB | C: $art, frei $freiVorher GB | " + $os.Caption)

# --- 2. Wiederherstellungspunkt -------------------------------------------
Titel 2 "Sicherheitsnetz: Wiederherstellungspunkt" "Safety net: restore point"
Info "Damit kann man Windows auf den Stand von jetzt zurücksetzen." "Lets you reset Windows to how it is right now."
# Immer, ohne Frage (Autor, 07.10.: "bitte immer einen Wiederherstellungspunkt erstellen, lass die Frage aus").
# Windows erlaubt sonst nur einen je 24 Stunden -- die Sperre kurz aufheben und danach wiederherstellen.
if (-not $Probe) {
    Info "Wiederherstellungspunkt wird angelegt, das dauert etwa eine Minute ..." "Creating a restore point, takes about a minute ..."
    $vorher = @(Get-ComputerRestorePoint -ErrorAction SilentlyContinue).Count
    $null = ImHintergrund -maxSek 180 -arbeit {
        $sr = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore'
        $alt = (Get-ItemProperty $sr -ErrorAction SilentlyContinue).SystemRestorePointCreationFrequency
        Enable-ComputerRestore -Drive "C:\"
        Set-ItemProperty $sr -Name SystemRestorePointCreationFrequency -Value 0 -Type DWord
        Checkpoint-Computer -Description "Vor PC-Cleaner" -RestorePointType MODIFY_SETTINGS
        if ($null -eq $alt) { Remove-ItemProperty $sr -Name SystemRestorePointCreationFrequency -ErrorAction SilentlyContinue }
        else { Set-ItemProperty $sr -Name SystemRestorePointCreationFrequency -Value $alt -Type DWord }
    }
    if (@(Get-ComputerRestorePoint -ErrorAction SilentlyContinue).Count -gt $vorher) { Notiz (T "Wiederherstellungspunkt 'Vor PC-Cleaner' angelegt." "Restore point 'Vor PC-Cleaner' created.") }
    else { Log ("   ⚠  " + (T "Wiederherstellungspunkt ging nicht (Computerschutz aus oder kein Platz). Es geht trotzdem weiter." "Restore point failed (system protection off or no space). Continuing anyway.")) $cOrange }
} else { Log (T "   [Test] Wiederherstellungspunkt würde jetzt angelegt." "   [Test] A restore point would be created now.") $cOrange }

# --- 3. Programme prüfen ----------------------------------------------------
# Autor, 07.10.: gezielt Programme suchen, die nicht gebraucht werden oder viel Leistung brauchen,
# je Programm: deaktivieren, löschen oder behalten. Bei Autor lief seit Februar unbemerkt "Salad".
Titel 3 "Programme prüfen: Leistungsfresser und lange nicht benutzt" "Check programs: resource hogs and unused ones"
Info "Einen Moment, ich messe die Auslastung und schaue nach, wann was zuletzt lief ..." "One moment, measuring load and checking when things last ran ..."
$schutz = 'Microsoft|Windows|Realtek|NVIDIA|AMD |AMD Chipset|AMD Software|Intel|Radeon|Defender|Security|VPN|Avast|AVG|Avira|Kaspersky|Bitdefender|Norton|McAfee|ESET|Malwarebytes|Sophos|Visual C\+\+|\.NET|Redistributable|Runtime|Driver|Treiber|Chipset|Hustle|MY\.GAMES|VeraCrypt|Java|Temurin|WebView|DirectX|Vulkan|PhysX|Office'
$rechner = 'Salad|NiceHash|Honeygain|Pawns|EarnApp|PacketStream|IPRoyal|Peer2Profit|Grass|Mysterium|Kryptex|Cudo|MinerGate|xmrig|nanominer|T-Rex|PhoenixMiner|lolMiner|GMiner|NBMiner|TeamRedMiner|Repocket|Traffmonetizer|Bitping|Medal|Overwolf|Wallpaper Engine'
$zuletzt = @{}
Get-ChildItem "$env:WINDIR\Prefetch\*.pf" -ErrorAction SilentlyContinue | ForEach-Object {
    $exe = ($_.Name -replace '-[0-9A-F]{8}\.pf$', '').ToUpper()
    if (-not $zuletzt.ContainsKey($exe) -or $zuletzt[$exe] -lt $_.LastWriteTime) { $zuletzt[$exe] = $_.LastWriteTime }
}
$m1 = @{}; Get-Process | Where-Object { $_.Path } | ForEach-Object { $m1[$_.Id] = $_.CPU }
Warte 3000
$last = @{}
Get-Process | Where-Object { $_.Path } | ForEach-Object {
    $cpuPct = if ($m1.ContainsKey($_.Id)) { [math]::Round(($_.CPU - $m1[$_.Id]) / 3 / [Environment]::ProcessorCount * 100, 1) } else { 0 }
    $ordner = Split-Path $_.Path -Parent
    if (-not $last.ContainsKey($ordner)) { $last[$ordner] = [pscustomobject]@{ Cpu = 0; Ram = 0 } }
    $last[$ordner].Cpu += $cpuPct; $last[$ordner].Ram += $_.WorkingSet64
}
$keys = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*','HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'
$programme = Get-ItemProperty $keys -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -and $_.UninstallString -and -not $_.SystemComponent -and $_.DisplayName -notmatch $schutz -and [string]$_.Publisher -notmatch $schutz } | Sort-Object DisplayName -Unique
$kandidaten = @()
foreach ($p in $programme) {
    Ui
    $ordner = [string]$p.InstallLocation
    if (-not $ordner -and $p.DisplayIcon) { $ordner = Split-Path (([string]$p.DisplayIcon -replace '"', '' -replace ',\s*-?\d+$', '')) -Parent }
    $ordner = $ordner.Trim().TrimEnd('\')
    if (-not (OrdnerOk $ordner)) { $ordner = "" }   # nie Windows/System32 oder Hauptordner
    $gruende = @()
    if ($p.DisplayName -match $rechner) { $gruende += (T "rechnet oder nimmt im Hintergrund auf (bekanntes Programm)" "computes or records in the background (known program)") }
    $cpuP = 0; $ram = 0
    if ($ordner -and (Test-Path $ordner)) {
        foreach ($k in $last.Keys) { if ($k -like "$ordner*") { $cpuP += $last[$k].Cpu; $ram += $last[$k].Ram } }
        if ($cpuP -ge 5) { $gruende += (T "braucht gerade $cpuP % Prozessor-Leistung" "uses $cpuP % CPU right now") }
        if ($ram -ge 400MB) { $gruende += (T ("braucht gerade " + (MB $ram) + " Arbeitsspeicher") ("uses " + (MB $ram) + " memory right now")) }
        $letzter = $null
        Get-ChildItem $ordner -Filter *.exe -File -Recurse -Depth 1 -ErrorAction SilentlyContinue | Select-Object -First 40 | ForEach-Object {
            $n = $_.Name.ToUpper(); if ($zuletzt.ContainsKey($n) -and ($null -eq $letzter -or $zuletzt[$n] -gt $letzter)) { $letzter = $zuletzt[$n] }
        }
        $installiert = $null; try { $installiert = [datetime]::ParseExact([string]$p.InstallDate, 'yyyyMMdd', $null) } catch { }
        if ($letzter -and $letzter -lt (Get-Date).AddDays(-90)) { $gruende += (T ("seit " + $letzter.ToString('dd.MM.yyyy') + " nicht mehr gestartet") ("not started since " + $letzter.ToString('yyyy-MM-dd'))) }
        elseif (-not $letzter -and $installiert -and $installiert -lt (Get-Date).AddDays(-90) -and $cpuP -lt 1) { $gruende += (T "seit der Installation nie gestartet (laut Windows)" "never started since installation (according to Windows)") }
    }
    if ($gruende.Count) { $kandidaten += [pscustomobject]@{ P = $p; Ordner = $ordner; Gruende = ($gruende -join "`n") } }
}
if ($kandidaten.Count -eq 0) { Info "Keine verdächtigen oder unbenutzten Programme gefunden." "No suspicious or unused programs found." }
else { Info ("Gefunden: " + $kandidaten.Count + " Programme. Du entscheidest bei jedem selbst.") ("Found: " + $kandidaten.Count + " programs. You decide for each one.") }
foreach ($k in $kandidaten) {
    $groesse = if ($k.P.EstimatedSize) { "  (" + [math]::Round($k.P.EstimatedSize / 1024) + " MB)" } else { "" }
    Log ("   •  " + $k.P.DisplayName + $groesse) $cText
    $wahl = 2
    if ($Probe) { Log (T "      [Test] Deaktivieren / Löschen / Behalten → Behalten" "      [Test] Disable / Uninstall / Keep → Keep") $cOrange }
    else {
        $wahl = Dialog ($k.P.DisplayName + $groesse) ($k.Gruende + "`n`n" + (T "Deaktivieren = bleibt installiert, läuft aber nicht mehr im Hintergrund.`nLöschen = richtig deinstallieren." "Disable = stays installed but no longer runs in the background.`nUninstall = remove it completely.")) `
                 @((T "Deaktivieren" "Disable"), (T "Löschen" "Uninstall"), (T "Behalten" "Keep")) @($cOrange, $cRot, $cGrau)
    }
    if ($wahl -eq 0 -and -not (OrdnerOk $k.Ordner)) {
        Log ("   ⚠  " + (T "Kann nicht sicher deaktiviert werden (kein eigener Programmordner) -- nichts geaendert." "Cannot be disabled safely (no own program folder) -- nothing changed.")) $cOrange
    }
    elseif ($wahl -eq 0) {
        # Nur was wirklich IM Programmordner liegt (ImOrdner) -- kein blosser Namensanfang (16.10.).
        Get-Process | Where-Object { $_.Path -and (ImOrdner $_.Path $k.Ordner) } | Stop-Process -Force -ErrorAction SilentlyContinue
        Get-CimInstance Win32_Service | Where-Object { $_.PathName -and (ImOrdner (ExeAus $_.PathName) $k.Ordner) } | ForEach-Object {
            Stop-Service -Name $_.Name -Force -ErrorAction SilentlyContinue; Set-Service -Name $_.Name -StartupType Disabled -ErrorAction SilentlyContinue }
        Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object { @($_.Actions | ForEach-Object { $_.Execute } | Where-Object { $_ -and (ImOrdner (ExeAus $_) $k.Ordner) }).Count -gt 0 } | Disable-ScheduledTask -ErrorAction SilentlyContinue | Out-Null
        $aus = [byte[]](0x03,0,0,0,0,0,0,0,0,0,0,0)
        foreach ($q in @(@{R='HKCU:\Software\Microsoft\Windows\CurrentVersion\Run';O='HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run'},
                         @{R='HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run';O='HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run'},
                         @{R='HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run';O='HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run32'})) {
            $r = Get-ItemProperty $q.R -ErrorAction SilentlyContinue
            if ($r) { $r.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' -and (ImOrdner (ExeAus ([string]$_.Value)) $k.Ordner) } | ForEach-Object {
                if (-not (Test-Path $q.O)) { New-Item -Path $q.O -Force | Out-Null }
                Set-ItemProperty -Path $q.O -Name $_.Name -Value $aus -Type Binary -ErrorAction SilentlyContinue } }
        }
        Notiz ((T "Deaktiviert: " "Disabled: ") + $k.P.DisplayName)
    }
    elseif ($wahl -eq 1) {
        # Nur ausfuehren, was wirklich zum Programm gehoert (Sicherheits-Update 16.10.): Der Eintrag
        # kann aus HKCU stammen, wo jedes Programm ohne Adminrechte schreiben darf -- blind mit
        # Adminrechten ausfuehren waere gefaehrlich. Darum: Die EXE muss im Programmordner des
        # Eintrags liegen, oder es ist msiexec (dann immer das aus System32). Ohne cmd /c, damit
        # kein "& boeses.exe" hinter dem Deinstaller mitlaeuft.
        $us = [string]$k.P.UninstallString
        $exe = ExeAus $us
        $rest = RestNach $us $exe
        $start = $null
        if ($exe -and ([IO.Path]::GetFileName($exe) -match '^msiexec(\.exe)?$')) {
            $start = Join-Path $env:WINDIR 'System32\msiexec.exe'
        } elseif ($exe -and $k.Ordner -and (OrdnerOk $k.Ordner) -and (ImOrdner $exe $k.Ordner) -and (Test-Path -LiteralPath $exe -PathType Leaf)) {
            $start = $exe
        }
        if (-not $start) {
            Log ("   ⚠  " + (T "Der Deinstaller liegt nicht im Programmordner -- aus Vorsicht nicht gestartet. Bitte über Windows-Einstellungen > Apps entfernen." "The uninstaller is not inside the program folder -- not started, to be safe. Please remove it via Windows Settings > Apps.")) $cOrange
        } else {
            Info "Der Deinstaller des Programms startet -- bitte dort bestätigen." "The program's uninstaller starts -- please confirm there."
            if ($rest) { $proc = Start-Process -FilePath $start -ArgumentList $rest -PassThru -ErrorAction SilentlyContinue }
            else { $proc = Start-Process -FilePath $start -PassThru -ErrorAction SilentlyContinue }
            while ($proc -and -not $proc.HasExited) { Warte 300 }
            Notiz ((T "Deinstalliert (oder dort abgebrochen): " "Uninstalled (or cancelled there): ") + $k.P.DisplayName)
        }
    }
}

# --- 4. Datenmüll -----------------------------------------------------------
Titel 4 "Datenmüll löschen" "Delete junk files"
$muell = @("$env:TEMP\*", "$env:WINDIR\Temp\*", "$env:WINDIR\SoftwareDistribution\Download\*", "$env:WINDIR\Minidump\*",
           "$env:LOCALAPPDATA\CrashDumps\*", "$env:LOCALAPPDATA\Microsoft\Windows\INetCache\*",
           "$env:LOCALAPPDATA\Microsoft\Windows\Explorer\thumbcache_*.db", "$env:ProgramData\Microsoft\Windows\WER\ReportArchive\*",
           "$env:ProgramData\Microsoft\Windows\WER\ReportQueue\*")
$g = Groesse $muell
Info ("Temp-Dateien, alte Update-Dateien, Absturzberichte, Vorschaubilder: etwa " + (MB $g)) ("Temp files, old update files, crash reports, thumbnails: about " + (MB $g))
if (Frage ("Etwa " + (MB $g) + " Datenmüll löschen?") ("Delete about " + (MB $g) + " of junk?")) {
    if (-not $Probe) {
        Wegraeumen $muell
        Delete-DeliveryOptimizationCache -Force -ErrorAction SilentlyContinue
        Notiz ((T "Datenmüll gelöscht: etwa " "Junk deleted: about ") + (MB $g))
    }
}
# Den Papierkorb leert der Reiniger nie -- darin kann versehentlich Geloeschtes liegen (Autor, 07.10.).

# --- 5. Browser & Apps (Zusatz-Ideen, Autor 07.10.) ---------------------------------
Titel 5 "Browser- und App-Zwischenspeicher" "Browser and app caches"
Info "Nur Zwischenspeicher -- Passwörter, Lesezeichen, Verlauf und Anmeldungen bleiben." "Caches only -- passwords, bookmarks, history and logins stay."
$browser = @()
foreach ($b in @(@{N="Chrome"; P="$env:LOCALAPPDATA\Google\Chrome\User Data"}, @{N="Edge"; P="$env:LOCALAPPDATA\Microsoft\Edge\User Data"},
                 @{N="Brave"; P="$env:LOCALAPPDATA\BraveSoftware\Brave-Browser\User Data"}, @{N="Opera"; P="$env:APPDATA\Opera Software\Opera Stable"},
                 @{N="Opera GX"; P="$env:APPDATA\Opera Software\Opera GX Stable"})) {
    if (Test-Path $b.P) {
        $pf = @(Get-ChildItem $b.P -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^(Default|Profile \d+)$' } | ForEach-Object { $_.FullName })
        if ($b.N -like "Opera*") { $pf = @($b.P) }
        foreach ($p in $pf) { $browser += "$p\Cache\*", "$p\Code Cache\*", "$p\GPUCache\*", "$p\Service Worker\CacheStorage\*" }
    }
}
Get-ChildItem "$env:LOCALAPPDATA\Mozilla\Firefox\Profiles" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $browser += ($_.FullName + "\cache2\*") }
$apps = @("$env:APPDATA\discord\Cache\*", "$env:APPDATA\discord\Code Cache\*", "$env:APPDATA\discord\GPUCache\*")
$gb = Groesse $browser; $ga = Groesse $apps
if (($gb + $ga) -gt 20MB) {
    if (Frage ("Zwischenspeicher von Browsern (" + (MB $gb) + ") und Discord (" + (MB $ga) + ") leeren?`nAm besten Browser und Discord vorher schließen.") ("Clear caches of browsers (" + (MB $gb) + ") and Discord (" + (MB $ga) + ")?`nBest close browsers and Discord first.")) {
        if (-not $Probe) {
            Wegraeumen ($browser + $apps)
            Notiz ((T "Browser- und Discord-Zwischenspeicher geleert: etwa " "Browser and Discord caches cleared: about ") + (MB ($gb + $ga)))
        }
    }
} else { Info "Browser- und App-Zwischenspeicher sind schon klein." "Browser and app caches are already small." }
# Spiele- und Grafik-Zwischenspeicher: werden beim naechsten Start neu angelegt.
$spiele = @("$env:LOCALAPPDATA\NVIDIA\DXCache\*", "$env:LOCALAPPDATA\NVIDIA\GLCache\*", "$env:LOCALAPPDATA\AMD\DxCache\*",
            "$env:LOCALAPPDATA\AMD\DxcCache\*", "$env:LOCALAPPDATA\D3DSCache\*", "$env:LOCALAPPDATA\Steam\htmlcache\*",
            "${env:ProgramFiles(x86)}\Steam\appcache\httpcache\*", "$env:LOCALAPPDATA\EpicGamesLauncher\Saved\webcache\*",
            "$env:LOCALAPPDATA\Spotify\Storage\*", "$env:APPDATA\Microsoft\Teams\Cache\*", "$env:APPDATA\Microsoft\Teams\GPUCache\*")
$gs = Groesse $spiele
if ($gs -gt 50MB) {
    if (Frage ("Spiele- und Grafik-Zwischenspeicher leeren? (" + (MB $gs) + ")`nGrafiktreiber, Steam, Epic, Spotify, Teams. Wird beim nächsten Start neu angelegt -- das erste Laden dauert dann einmal etwas länger.") ("Clear game and graphics caches? (" + (MB $gs) + ")`nGraphics drivers, Steam, Epic, Spotify, Teams. Rebuilt on next start -- the first load takes a bit longer once.")) {
        if (-not $Probe) { Wegraeumen $spiele; Notiz ((T "Spiele- und Grafik-Zwischenspeicher geleert: etwa " "Game and graphics caches cleared: about ") + (MB $gs)) }
    }
}
if (-not $Probe) { ipconfig /flushdns | Out-Null }
Info "DNS-Zwischenspeicher geleert (hilft bei Verbindungsproblemen)." "DNS cache flushed (helps with connection problems)."

# --- 6. Windows-Reste ---------------------------------------------------------------
Titel 6 "Windows-Reste aufräumen" "Clean up Windows leftovers"
if (Frage "Alte Windows-Update-Bausteine aufräumen (WinSxS)?`nSicher, gibt oft 1-5 GB frei, dauert aber 5-15 Minuten." "Clean up old Windows update components (WinSxS)?`nSafe, often frees 1-5 GB, but takes 5-15 minutes.") {
    if (-not $Probe) {
        if (Get-Process -Name TiWorker -ErrorAction SilentlyContinue) {
            # Windows installiert gerade Updates -- DISM wuerde warten, bis das fertig ist (Haenger 07.10.).
            Log ("   ⚠  " + (T "Windows installiert gerade selbst Updates -- dieser Teil wird uebersprungen. Spaeter noch einmal laufen lassen." "Windows is installing updates right now -- this part is skipped. Run it again later.")) $cOrange
        } else {
            Info "Läuft ... bitte warten (höchstens 20 Minuten)." "Running ... please wait (at most 20 minutes)."
            if (ImHintergrund { Dism.exe /Online /Cleanup-Image /StartComponentCleanup /Quiet | Out-Null } 1200) {
                Notiz (T "Windows-Update-Reste aufgeräumt." "Windows update leftovers cleaned up.")
            }
        }
    }
}
if (Test-Path "$env:SystemDrive\Windows.old") {
    if (Frage "Alte Windows-Installation 'Windows.old' entfernen?`nGeht ueber die Windows-eigene Datentraegerbereinigung. Danach kann man nicht mehr auf die alte Windows-Fassung zurueck." "Remove the old Windows installation 'Windows.old'?`nUses Windows' own Disk Cleanup. Afterwards you cannot go back to the old Windows version.") {
        if (-not $Probe) {
            $vc = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\VolumeCaches\Previous Installations'
            if (Test-Path $vc) {
                Set-ItemProperty $vc -Name StateFlags0077 -Value 2 -Type DWord
                ImHintergrund { Start-Process cleanmgr.exe -ArgumentList '/sagerun:77' -Wait } 1800 | Out-Null
                Remove-ItemProperty $vc -Name StateFlags0077 -ErrorAction SilentlyContinue
                Notiz (T "Windows.old ueber die Datentraegerbereinigung entfernt." "Windows.old removed via Disk Cleanup.")
            }
        }
    }
}
$hib = Get-Item "$env:SystemDrive\hiberfil.sys" -Force -ErrorAction SilentlyContinue
if ($hib -and $hib.Length -gt 1GB) {
    if (Frage ("Ruhezustand-Datei entfernen? Gibt " + (MB $hib.Length) + " frei.`nDanach gibt es keinen 'Ruhezustand' und keinen Schnellstart mehr -- Herunterfahren und Energiesparen gehen normal.") ("Remove the hibernation file? Frees " + (MB $hib.Length) + ".`nAfterwards there is no 'hibernate' and no fast startup -- shut down and sleep work as usual.")) {
        if (-not $Probe) { powercfg /hibernate off; Notiz ((T "Ruhezustand-Datei entfernt: " "Hibernation file removed: ") + (MB $hib.Length)) }
    }
}

# --- 7. Autostart -----------------------------------------------------------
Titel 7 "Autostart ausmisten" "Clean up autostart"
Info "Abgeschaltet heißt nur: startet nicht mehr mit Windows. Zurückholen: Task-Manager → Autostart → Aktivieren." "Disabled only means: no longer starts with Windows. Undo: Task Manager → Startup → Enable."
$behalten = 'SecurityHealth|Defender|VPN|Avast|AVG|Avira|Kaspersky|Bitdefender|Norton|McAfee|ESET|Malwarebytes|Sophos|Realtek|Rtk|Audio|Nahimic|Waves|igfx|Intel|NVIDIA|nvcpl|AMD.*Driver|RadeonSettings|MY\.GAMES|mglauncher|HustleBot|Hustle|VeraCrypt|Synaptics|ETD|Elan|Touchpad|Bluetooth|OneDrive|Logitech|NZXT|Backup|Sicherung|Acronis|Macrium|Veeam|Dropbox|Google Drive|GoogleDrive|iCloud|Bitwarden|1Password|KeePass|Nextcloud'
$eintraege = @()
$quellen = @(
  @{ Run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run';             Ok = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run' },
  @{ Run = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run';             Ok = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run' },
  @{ Run = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'; Ok = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run32' })
foreach ($q in $quellen) {
    $p = Get-ItemProperty $q.Run -ErrorAction SilentlyContinue
    if ($p) { foreach ($e in $p.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' }) { $eintraege += [pscustomobject]@{ Name = $e.Name; Befehl = [string]$e.Value; Ok = $q.Ok } } }
}
foreach ($o in @("$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup", "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp")) {
    $okPfad = if ($o -like "$env:APPDATA*") { 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder' } else { 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder' }
    Get-ChildItem $o -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne 'desktop.ini' } | ForEach-Object { $eintraege += [pscustomobject]@{ Name = $_.Name; Befehl = $_.FullName; Ok = $okPfad } }
}
function SchonAus($e) {
    $w = (Get-ItemProperty $e.Ok -ErrorAction SilentlyContinue).($e.Name)
    return ($w -is [byte[]] -and $w.Length -gt 0 -and ($w[0] -band 1))
}
$vorschlag = @($eintraege | Where-Object { ($_.Name + " " + $_.Befehl) -notmatch $behalten -and -not (SchonAus $_) })
if ($vorschlag.Count -eq 0) { Info "Nichts Unnötiges im Autostart gefunden." "Nothing unnecessary found in autostart." }
else {
    Info ("Vorschlag zum Abschalten: " + (($vorschlag | ForEach-Object { $_.Name }) -join ", ")) ("Suggested to disable: " + (($vorschlag | ForEach-Object { $_.Name }) -join ", "))
    $alle = Frage ("Alle " + $vorschlag.Count + " abschalten?`n(Nein = bei jedem einzeln entscheiden)`n`n" + (($vorschlag | ForEach-Object { "• " + $_.Name }) -join "`n")) `
                  ("Disable all " + $vorschlag.Count + "?`n(No = decide one by one)`n`n" + (($vorschlag | ForEach-Object { "• " + $_.Name }) -join "`n"))
    $aus = [byte[]](0x03,0,0,0,0,0,0,0,0,0,0,0)
    foreach ($v in $vorschlag) {
        $ja = $alle
        if (-not $alle) { $ja = Frage ("'" + $v.Name + "' nicht mehr mit Windows starten?") ("Stop '" + $v.Name + "' from starting with Windows?") }
        if ($ja -and -not $Probe) {
            if (-not (Test-Path $v.Ok)) { New-Item -Path $v.Ok -Force | Out-Null }
            Set-ItemProperty -Path $v.Ok -Name $v.Name -Value $aus -Type Binary -ErrorAction SilentlyContinue
            Notiz ((T "Autostart aus: " "Autostart off: ") + $v.Name)
        }
    }
}

# Hintergrund-Aufgaben von Programmen (Updater, die heimlich starten) -- umkehrbar.
$aufgaben = @(Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
    $_.State -ne 'Disabled' -and $_.TaskPath -notlike '\Microsoft\*' -and ($_.TaskName + " " + (($_.Actions | ForEach-Object { $_.Execute }) -join " ")) -notmatch ($behalten + '|OneDrive|Defender|Driver|Treiber') })
if ($aufgaben.Count) {
    Info ("Hintergrund-Aufgaben von Programmen: " + (($aufgaben | ForEach-Object { $_.TaskName }) -join ", ")) ("Background tasks of programs: " + (($aufgaben | ForEach-Object { $_.TaskName }) -join ", "))
    $alleA = Frage ("Diese " + $aufgaben.Count + " Hintergrund-Aufgaben abschalten? (meist Updater)`n(Nein = einzeln entscheiden. Zurückholen: Aufgabenplanung.)`n`n" + (($aufgaben | Select-Object -First 12 | ForEach-Object { "• " + $_.TaskName }) -join "`n")) `
                   ("Disable these " + $aufgaben.Count + " background tasks? (mostly updaters)`n(No = decide one by one. Undo: Task Scheduler.)`n`n" + (($aufgaben | Select-Object -First 12 | ForEach-Object { "• " + $_.TaskName }) -join "`n"))
    foreach ($a in $aufgaben) {
        $ja = $alleA
        if (-not $alleA) { $ja = Frage ("Hintergrund-Aufgabe '" + $a.TaskName + "' abschalten?") ("Disable background task '" + $a.TaskName + "'?") }
        if ($ja -and -not $Probe) { $a | Disable-ScheduledTask -ErrorAction SilentlyContinue | Out-Null; Notiz ((T "Hintergrund-Aufgabe aus: " "Background task off: ") + $a.TaskName) }
    }
}

# --- 8. Hintergrund-Bremsen -----------------------------------------------------
Titel 8 "Hintergrund-Bremsen abschalten" "Turn off background brakes"
if (Frage "Xbox Game Bar und Hintergrund-Aufnahme abschalten?`n(Die nimmt sonst ständig das Spiel auf und kostet Leistung.)" "Turn off Xbox Game Bar and background recording?`n(Otherwise it keeps recording the game and costs performance.)") {
    if (-not $Probe) {
        New-Item 'HKCU:\System\GameConfigStore' -Force | Out-Null
        Set-ItemProperty 'HKCU:\System\GameConfigStore' -Name GameDVR_Enabled -Value 0 -Type DWord
        New-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' -Force | Out-Null
        Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' -Name AppCaptureEnabled -Value 0 -Type DWord
        Notiz (T "Game Bar-Aufnahme aus." "Game Bar recording off.")
    }
}
if (Frage "Hintergrund-Apps und Werbe-Tipps von Windows abschalten?" "Turn off background apps and Windows ads/tips?") {
    if (-not $Probe) {
        New-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications' -Force | Out-Null
        Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications' -Name GlobalUserDisabled -Value 1 -Type DWord
        $cdm = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'
        foreach ($n in 'SystemPaneSuggestionsEnabled','SoftLandingEnabled','SubscribedContent-338388Enabled','SubscribedContent-338389Enabled','SubscribedContent-353694Enabled','SubscribedContent-353696Enabled','SilentInstalledAppsEnabled') {
            Set-ItemProperty $cdm -Name $n -Value 0 -Type DWord -ErrorAction SilentlyContinue
        }
        Notiz (T "Hintergrund-Apps und Windows-Tipps aus." "Background apps and Windows tips off.")
    }
}

if (Frage "Windows-Updates nicht mehr an fremde PCs im Internet hochladen?`n(Windows verteilt Updates sonst weiter und bremst damit die Leitung.)" "Stop uploading Windows updates to other PCs on the internet?`n(Otherwise Windows shares updates and slows your connection.)") {
    if (-not $Probe) {
        New-Item 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\DeliveryOptimization\Config' -Force | Out-Null
        Set-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\DeliveryOptimization\Config' -Name DODownloadMode -Value 0 -Type DWord
        Notiz (T "Update-Verteilung an fremde PCs aus." "Update sharing with other PCs off.")
    }
}
if (Frage "Widgets, News und den Copilot-Knopf in der Taskleiste ausblenden?`n(Laufen sonst im Hintergrund mit.)" "Hide widgets, news and the Copilot button in the taskbar?`n(Otherwise they keep running in the background.)") {
    if (-not $Probe) {
        $adv = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'
        Set-ItemProperty $adv -Name TaskbarDa -Value 0 -Type DWord -ErrorAction SilentlyContinue
        Set-ItemProperty $adv -Name ShowCopilotButton -Value 0 -Type DWord -ErrorAction SilentlyContinue
        New-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Feeds' -Force | Out-Null
        Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Feeds' -Name ShellFeedsTaskbarViewMode -Value 2 -Type DWord -ErrorAction SilentlyContinue
        Notiz (T "Widgets, News und Copilot-Knopf ausgeblendet (falls Windows es zulässt)." "Widgets, news and Copilot button hidden (if Windows allows it).")
    }
}

# --- 7. Leistung (jede Einstellung einzeln, Autor 07.10.) ------------------------
Titel 9 "Windows auf Leistung stellen" "Set Windows to performance"
if (Frage "Windows-Animationen ausschalten?`n(Fenster und Menüs gehen sofort auf. Das Spiel sieht genauso aus, der Bot merkt nichts.)" "Turn off Windows animations?`n(Windows and menus open instantly. The game looks the same, the bot is not affected.)") {
    if (-not $Probe) {
        Add-Type -Namespace PR -Name S -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SystemParametersInfo(uint a, uint b, IntPtr c, uint d);
[DllImport("user32.dll")] public static extern bool SystemParametersInfo(uint a, uint b, ref ANIM c, uint d);
[StructLayout(LayoutKind.Sequential)] public struct ANIM { public uint cbSize; public int iMinAnimate; }
'@
        $a = New-Object PR.S+ANIM; $a.cbSize = 8; $a.iMinAnimate = 0
        [PR.S]::SystemParametersInfo(0x0049, 8, [ref]$a, 3) | Out-Null
        foreach ($spi in 0x1043, 0x1003, 0x1005, 0x1007, 0x1017, 0x1015, 0x1013) { [PR.S]::SystemParametersInfo($spi, 0, [IntPtr]::Zero, 3) | Out-Null }
        Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced' -Name TaskbarAnimations -Value 0 -Type DWord
        Notiz (T "Animationen aus." "Animations off.")
    }
}
if (Frage "Energieplan auf 'Höchstleistung' stellen?`n(Prozessor taktet nicht mehr herunter. Etwas mehr Stromverbrauch.)" "Set the power plan to 'High performance'?`n(The CPU no longer clocks down. Slightly more power use.)") {
    if (-not $Probe) {
        $hp = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c'
        if (-not (powercfg /list | Select-String $hp)) { powercfg -duplicatescheme $hp | Out-Null }
        powercfg -setactive $hp
        Notiz (T "Energieplan: Höchstleistung." "Power plan: high performance.")
    }
}
if ($art -eq "HDD") {
    if (Frage "Windows-Suche (Indizierung) auf 'nur bei Bedarf' stellen?`nAuf alten Festplatten liest sie ständig im Hintergrund. Die Suche geht danach weiter, nur etwas langsamer." "Set Windows Search (indexing) to 'on demand only'?`nOn old hard disks it keeps reading in the background. Search still works afterwards, just a bit slower.") {
        if (-not $Probe) { Stop-Service WSearch -Force -ErrorAction SilentlyContinue; Set-Service WSearch -StartupType Manual -ErrorAction SilentlyContinue; Notiz (T "Windows-Suche läuft nur noch bei Bedarf." "Windows Search now runs on demand only.") }
    }
}
if (Frage "Transparenz (durchsichtige Taskleiste und Menüs) ausschalten?" "Turn off transparency (see-through taskbar and menus)?") {
    if (-not $Probe) {
        Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' -Name EnableTransparency -Value 0 -Type DWord
        Notiz (T "Transparenz aus." "Transparency off.")
    }
}

# --- 8. Festplatte ------------------------------------------------------------------
Titel 10 "Festplatte optimieren" "Optimise the drive"
$frageText = if ($art -eq "HDD") { (T "Alte Festplatte (HDD) jetzt defragmentieren?`nMacht sie schneller, dauert aber 10 bis 60 Minuten." "Defragment the old hard disk (HDD) now?`nMakes it faster but takes 10 to 60 minutes.") }
             else { (T "SSD jetzt aufräumen ('Trim')? Dauert unter einer Minute." "Tidy up the SSD now ('trim')? Takes under a minute.") }
if (Frage $frageText $frageText) {
    if (-not $Probe) {
        Info "Läuft ... das Fenster bleibt so lange offen." "Running ... the window stays open meanwhile."
        if ($art -eq "HDD") { ImHintergrund { Optimize-Volume -DriveLetter C -Defrag } 3600 | Out-Null } else { ImHintergrund { Optimize-Volume -DriveLetter C -ReTrim } 600 | Out-Null }
        Notiz (T "Laufwerk C optimiert." "Drive C optimised.")
    }
}

# --- 11. Gesundheit & Viren -------------------------------------------------------------
Titel 11 "Gesundheit und Viren prüfen" "Check health and viruses"
$gesund = Get-PhysicalDisk | Where-Object { $_.DeviceId -eq $sysDisk.Number } | Select-Object -First 1
$rel = $gesund | Get-StorageReliabilityCounter -ErrorAction SilentlyContinue
$zustand = [string]$gesund.HealthStatus
$extra = @()
if ($rel.Temperature) { $extra += (T "Temperatur " "temperature ") + $rel.Temperature + " °C" }
if ($rel.Wear -ne $null) { $extra += (T "Abnutzung " "wear ") + $rel.Wear + " %" }
if ($rel.ReadErrorsUncorrected) { $extra += (T "nicht behebbare Lesefehler: " "uncorrectable read errors: ") + $rel.ReadErrorsUncorrected }
$zt = (T "Laufwerk C: Zustand " "Drive C: health ") + $zustand + $(if ($extra) { " (" + ($extra -join ", ") + ")" } else { "" })
if ($zustand -eq "Healthy" -and -not $rel.ReadErrorsUncorrected) { Notiz $zt }
else {
    Log ("   ⚠  " + $zt) $cRot; $bericht.Add($zt)
    $bericht.Add((T "ACHTUNG: Das Laufwerk meldet Probleme -- wichtige Daten sichern! Ein kaputtes Laufwerk macht alles langsam." "WARNING: the drive reports problems -- back up important data! A failing drive slows everything down."))
}
$av = Get-MpComputerStatus -ErrorAction SilentlyContinue
if ($av -and $av.AntivirusEnabled) {
    # Autor, 07.10.: den langen Scan als Auswahl, mit Hinweis auf bis zu 120 Minuten.
    $scan = 0   # 0 = schnell, 1 = vollstaendig, 2 = keiner
    if ($Probe) { Log (T "   [Test] Virenscan: Schnellscan / Vollständiger Scan / Kein Scan -> Schnellscan" "   [Test] Virus scan: quick / full / none -> quick") $cOrange }
    else {
        $scan = Dialog (T "Virenscan mit dem Windows-Virenschutz" "Virus scan with Windows virus protection") `
            (T "Findet Viren, Trojaner, versteckte Miner und Spionage-Programme.`n`n• Schnellscan: prüft laufende Programme, Autostart, Dienste und Windows-Ordner -- dauert 2-10 Minuten.`n• Vollständiger Scan: prüft JEDE Datei auf allen Laufwerken -- dauert bis zu 120 Minuten. Der PC kann dabei langsamer sein." `
               "Finds viruses, trojans, hidden miners and spyware.`n`n• Quick scan: checks running programs, autostart, services and Windows folders -- takes 2-10 minutes.`n• Full scan: checks EVERY file on all drives -- takes up to 120 minutes. The PC may be slower meanwhile.") `
            @((T "Schnellscan" "Quick scan"), (T "Lang (bis 120 Min.)" "Full (up to 120 min)"), (T "Kein Scan" "No scan")) @($cBlau, $cOrange, $cGrau)
    }
    if ($scan -eq 1) { $script:dauer[11] = 3600; Fortschritt }
    if ($scan -lt 2 -and -not $Probe) {
        $seit = Get-Date
        if ($scan -eq 1) {
            Info "Vollständiger Virenscan läuft (bis zu 120 Minuten) -- der PC kann nebenbei benutzt werden." "Full virus scan running (up to 120 minutes) -- you can keep using the PC."
            ImHintergrund { Update-MpSignature -ErrorAction SilentlyContinue; Start-MpScan -ScanType FullScan } 7500 | Out-Null
        } else {
            Info "Schnellscan läuft (2-10 Minuten) ..." "Quick scan running (2-10 minutes) ..."
            ImHintergrund { Update-MpSignature -ErrorAction SilentlyContinue; Start-MpScan -ScanType QuickScan } 900 | Out-Null
        }
        $funde = @(Get-MpThreatDetection -ErrorAction SilentlyContinue | Where-Object { $_.InitialDetectionTime -gt $seit.AddMinutes(-1) })
        $art2 = if ($scan -eq 1) { (T "Vollständiger Virenscan" "Full virus scan") } else { (T "Virenscan" "Virus scan") }
        if ($funde.Count) { Log ("   ⚠  " + $art2 + ": " + $funde.Count + (T " Bedrohung(en) gefunden -- Windows-Sicherheit öffnen und ansehen." " threat(s) found -- open Windows Security and check.")) $cRot; $bericht.Add($art2 + ": " + $funde.Count + (T " Funde" " detections")) }
        else { Notiz ($art2 + (T ": nichts gefunden." ": nothing found.")) }
    }
} else { Info "Ein anderer Virenschutz ist aktiv -- bitte dort einen Scan starten." "Another antivirus is active -- please run a scan there." }

# --- Bericht ---------------------------------------------------------------------------
Titel 12 "Fertig!" "Done!"
Balken 12
$letztes = $script:schrittLabels[-1]; $letztes.Text = "✔   " + $letztes.Text.Substring(4); $letztes.ForeColor = $cGruen
$freiNachher = FreiC
$gewonnen = [math]::Round($freiNachher - $freiVorher, 1)
if ($gewonnen -ge 0) { Notiz (T "Platz frei: vorher $freiVorher GB, jetzt $freiNachher GB (+$gewonnen GB)" "Free space: before $freiVorher GB, now $freiNachher GB (+$gewonnen GB)") }
else { Notiz (T ("Platz frei: vorher $freiVorher GB, jetzt $freiNachher GB -- der Wiederherstellungspunkt braucht selbst etwas Platz.") ("Free space: before $freiVorher GB, now $freiNachher GB -- the restore point itself needs some space.")) }
$tipps = @()
if ($art -eq "HDD") { $tipps += (T "Die größte Bremse ist die alte Festplatte (HDD). Eine SSD (ca. 30-40 Euro) halbiert die Ladezeiten im Spiel ungefähr." "The biggest brake is the old hard disk (HDD). An SSD (about 30-40 euros) roughly halves the loading times in the game.") }
if ($ramGB -lt 8) { $tipps += (T "Nur $ramGB GB Arbeitsspeicher -- 8 GB oder mehr würden spürbar helfen." "Only $ramGB GB memory -- 8 GB or more would help noticeably.") }
if ($freiNachher -lt 15) { $tipps += (T "Auf C ist wenig Platz frei -- unter 15 GB wird Windows langsam." "Little free space on C -- below 15 GB Windows gets slow.") }
# Platzfresser: die groessten eigenen Dateien -- nur anzeigen, nie loeschen.
$ich = [Environment]::GetFolderPath("UserProfile")
$gross = @(Get-ChildItem $ich -File -Recurse -Force -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "$ich\AppData\*" -and $_.Length -gt 1GB } | Sort-Object Length -Descending | Select-Object -First 5)
if ($gross.Count) {
    $tipps += (T "Deine größten Dateien (selbst entscheiden, ob du sie noch brauchst):" "Your largest files (decide yourself whether you still need them):")
    foreach ($d in $gross) { $tipps += ("   " + (MB $d.Length) + "  " + $d.FullName) }
}
# Downloads nur ansehen, nie loeschen (persoenliche Dateien).
$alt = @(Get-ChildItem ([Environment]::GetFolderPath("UserProfile") + "\Downloads") -File -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-90) })
$altGr = ($alt | Measure-Object Length -Sum).Sum
if ($altGr -gt 500MB) { $tipps += (T ("Im Ordner Downloads liegen " + (MB $altGr) + " in Dateien, die älter als 3 Monate sind -- selbst durchsehen und Unnötiges löschen.") ("Your Downloads folder has " + (MB $altGr) + " in files older than 3 months -- have a look and delete what you do not need.")) }
$tipps += (T "Beim Bot-Spielen alle anderen Programme schließen (Browser, Discord-App, Spiele-Launcher)." "While the bot plays, close all other programs (browser, Discord app, game launchers).")
Log ""; Log (T "Ehrliche Einschätzung:" "Honest assessment:") $cBlau -fett
foreach ($t in $tipps) { Notiz $t }
$datei = Join-Path ([Environment]::GetFolderPath("Desktop")) "PC-Cleaner-Bericht.txt"
if (-not $Probe) { $bericht | Set-Content -Path $datei -Encoding UTF8 }
$script:fertig = $true
if ($Probe) {
    Warte 800
    # Im Testmodus ein Foto des Fensters ablegen (zur Kontrolle des Aussehens).
    $bmp = New-Object Drawing.Bitmap($script:haupt.Width, $script:haupt.Height)
    $script:haupt.DrawToBitmap($bmp, (New-Object Drawing.Rectangle(0, 0, $bmp.Width, $bmp.Height)))
    $bmp.Save("$env:TEMP\pc-reiniger-probe.png"); $script:haupt.Close(); return
}
[System.Media.SystemSounds]::Asterisk.Play()
# Autor, 07.10.: bei 100 % eine Meldung "PC-Cleaner abgeschlossen" -- OK schliesst den Reiniger.
# "OK" steht hinten: Wer das Fenster mit dem X schliesst, bekommt dasselbe wie mit OK.
$w = Dialog (T "✔  PC-Cleaner abgeschlossen" "✔  PC cleaner finished") ((T "100 % -- alles erledigt.`nAm besten den PC jetzt einmal neu starten.`n`nDer Bericht liegt auf dem Desktop: PC-Cleaner-Bericht.txt`n`nMit OK wird der PC-Cleaner geschlossen." "100 % -- all done.`nBest restart the PC now.`n`nThe report is on the desktop: PC-Cleaner-Bericht.txt`n`nOK closes the PC cleaner.")) `
            @((T "Bericht ansehen" "View report"), "OK") @($cGrau, $cBlau)
if ($w -eq 0) { Start-Process notepad.exe $datei }
$script:haupt.Close()

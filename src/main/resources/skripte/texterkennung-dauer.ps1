# =====================================================================
#  Texterkennung, die offen bleibt
# =====================================================================
#
#  WARUM ES DIESES ZWEITE SKRIPT GIBT
#
#  texterkennung.ps1 wird fuer jeden einzelnen Blick neu gestartet.
#  Gemessen am 4. September 2026:
#
#      ein Lesevorgang insgesamt   335 ms
#      davon blosser Start          160 ms
#
#  Fast die Haelfte geht also fuer Hochfahren, WinRT laden und wieder
#  Beenden drauf -- jedes Mal aufs Neue, obwohl sich daran nie etwas
#  aendert. Die Arena liest in einer Runde ein Dutzend Mal; das summiert
#  sich zu Sekunden, die der Bot beim Angreifen fehlen.
#
#  Dieses Skript wird EINMAL gestartet und bleibt offen. Die WinRT-Typen
#  und die Erkennungsmodelle werden einmal geladen und dann
#  wiederverwendet.
#
#  WIE DIE VERSTAENDIGUNG LAEUFT
#
#  Hinein, eine Zeile je Auftrag:
#
#      <sprache><TAB><pfad zum bild>
#
#  Die Sprache darf leer sein -- dann die erste in Windows eingerichtete.
#
#  Heraus: die erkannten Zeilen, danach immer eine Zeile "--ENDE--".
#  Daran erkennt die Gegenseite, dass der Auftrag fertig ist. Ohne so ein
#  Schlusszeichen wuesste sie nie, ob noch etwas kommt, und wuerde auf
#  jeden Auftrag bis zur Zeitgrenze warten.
#
#  Ging etwas schief, kommt vor dem --ENDE-- eine Zeile "--FEHLER-- ...".
#  Auch dann wird das Schlusszeichen geschrieben: ein Fehler darf die
#  Gegenseite nicht haengen lassen.
#
#  WIE ES AUFHOERT
#
#  Bei "--QUIT--", oder wenn die Eingabe zu Ende ist. Letzteres ist der
#  wichtige Fall: stirbt der Bot -- auch hart abgeschossen --, schliesst
#  Windows die Leitung, ReadLine gibt nichts mehr zurueck, und dieses
#  Skript beendet sich von selbst. Sonst wuerde sich mit jedem Neustart
#  ein weiteres PowerShell ansammeln.
# =====================================================================

$ErrorActionPreference = "Stop"

# UTF-8 in beide Richtungen. Ohne das kommen kyrillische Namen als
# Fragezeichen an -- und genau dafuer ist das kyrillische Modell da.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime

    # WinRT-Aufrufe sind alle asynchron. Diese Hilfe macht daraus etwas,
    # auf das sich in PowerShell warten laesst.
    $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object {
            $_.Name -eq 'AsTask' -and
            $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]

    function Warte($vorgang, $typ) {
        $m = $asTask.MakeGenericMethod($typ)
        $task = $m.Invoke($null, @($vorgang))
        $task.Wait(-1) | Out-Null
        return $task.Result
    }

    [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime] | Out-Null

    # Die Modelle werden gemerkt. Ein Modell zu erzeugen kostet Zeit --
    # und in diesem Skript passiert das jetzt hoechstens zweimal
    # ueberhaupt, statt bei jedem einzelnen Blick.
    $modelle = @{}

    function HolModell($sprache) {
        if ($modelle.ContainsKey($sprache)) { return $modelle[$sprache] }

        if ([string]::IsNullOrWhiteSpace($sprache)) {
            $m = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
        } else {
            $tag = [Windows.Globalization.Language]::new($sprache)
            $m = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($tag)
        }
        $modelle[$sprache] = $m
        return $m
    }

} catch {
    [Console]::Out.WriteLine("--STARTFEHLER-- " + $_.Exception.Message)
    [Console]::Out.Flush()
    exit 2
}

# Erst jetzt melden. Die Gegenseite wartet auf dieses Wort, bevor sie den
# ersten Auftrag schickt -- so faellt der einmalige Ladeaufwand nicht in
# die Messung des ersten Lesevorgangs.
[Console]::Out.WriteLine("--BEREIT--")
[Console]::Out.Flush()

while ($true) {

    $zeile = [Console]::In.ReadLine()
    if ($null -eq $zeile) { break }          # Leitung zu, der Bot ist weg
    if ($zeile -eq "--QUIT--") { break }
    if ([string]::IsNullOrWhiteSpace($zeile)) { continue }

    try {
        $teile = $zeile -split "`t", 2
        if ($teile.Count -lt 2) {
            throw "Auftrag ohne Pfad: $zeile"
        }
        $sprache = $teile[0]
        $bild    = $teile[1]

        if (-not (Test-Path -LiteralPath $bild)) {
            throw "Bild nicht gefunden: $bild"
        }

        $modell = HolModell $sprache
        if ($null -eq $modell) {
            throw "Fuer '$sprache' ist keine Texterkennung eingerichtet."
        }

        $datei   = Warte ([Windows.Storage.StorageFile]::GetFileFromPathAsync($bild)) ([Windows.Storage.StorageFile])
        $strom   = Warte ($datei.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        $decoder = Warte ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($strom)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap  = Warte ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

        $ergebnis = Warte ($modell.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

        foreach ($z in $ergebnis.Lines) {
            [Console]::Out.WriteLine($z.Text)
        }

        # Aufraeumen. Ohne das haelt dieses Skript, das ja stundenlang
        # laeuft, jedes je gelesene Bild im Speicher fest.
        $bitmap.Dispose()
        $strom.Dispose()

    } catch {
        [Console]::Out.WriteLine("--FEHLER-- " + $_.Exception.Message)
    }

    # IMMER. Auch nach einem Fehler, sonst wartet die Gegenseite bis zur
    # Zeitgrenze auf ein Ende, das nie kommt.
    [Console]::Out.WriteLine("--ENDE--")
    [Console]::Out.Flush()
}

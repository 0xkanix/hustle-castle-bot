# =====================================================================
#  Text aus einem Bild lesen -- mit der Texterkennung, die in Windows
#  schon eingebaut ist.
# =====================================================================
#
#  Aufruf:   powershell -File texterkennung.ps1 -Bild "C:\...\chat.png"
#  Ausgabe:  der erkannte Text, Zeile fuer Zeile, auf der Standardausgabe
#
#  WARUM NICHT TESSERACT
#
#  Der uebliche Weg waere ein eigenes Leseprogramm gewesen -- Tesseract,
#  rund 50 MB, dazu Sprachdateien. Windows bringt aber seit Jahren eine
#  eigene Texterkennung mit (Windows.Media.Ocr), und die hat den Clan-Chat
#  im Test Wort fuer Wort richtig gelesen:
#
#      "die Pokale die ma hatte... aus dem Game rausgehen, halbe
#       Stunde warten und das wiederholen"
#
#  Nichts herunterladen, nichts installieren, und der Text verlaesst den
#  Rechner nicht. Das war die bessere Wahl.
#
#  WELCHE SPRACHEN
#
#  Erkannt wird in den Sprachen, die in Windows eingerichtet sind. Deutsch
#  ist da. Englisch wird mit dem deutschen Modell ebenfalls brauchbar
#  gelesen -- beides ist lateinische Schrift. Fuer Russisch (Kyrillisch)
#  muesste in den Windows-Einstellungen die Sprache Russisch hinzugefuegt
#  werden; ohne das bleibt kyrillischer Text unlesbar.
# =====================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$Bild,

    # Leer heisst: die Sprache nehmen, die in Windows an erster Stelle
    # steht. "ru" fordert ausdruecklich das kyrillische Modell an.
    [string]$Sprache = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Bild)) {
    Write-Error "Bild nicht gefunden: $Bild"
    exit 1
}

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

    $datei    = Warte ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Bild)) ([Windows.Storage.StorageFile])
    $strom    = Warte ($datei.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder  = Warte ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($strom)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap   = Warte ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

    # ------------------------------------------------------------------
    #  Welche Sprache?
    # ------------------------------------------------------------------
    #
    #  Hier stand nur TryCreateFromUserProfileLanguages(). Das nimmt die
    #  erste eingerichtete Sprache -- auf diesem Rechner Deutsch -- und
    #  sonst keine. Windows hat aber laengst auch Russisch:
    #
    #      de-DE  Deutsch (Deutschland)
    #      ru     Russisch
    #
    #  Und in der Arena heissen Autors Gegner Dinge wie ~ЦИРИ~. Die hat
    #  das deutsche Modell nie lesen koennen -- nicht weil Windows es
    #  nicht kann, sondern weil nie danach gefragt wurde. Im Notizblock
    #  blieben genau die Gegner leer, die Autor am meisten interessieren.
    #
    #  Mit -Sprache ru laesst sich jetzt gezielt das kyrillische Modell
    #  anfordern. Ohne Angabe bleibt alles wie bisher.
    $engine = $null
    if ($Sprache) {
        # Der Sprachtyp muss eigens geladen werden -- WinRT-Typen sind in
        # PowerShell nicht von allein da.
        [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime] | Out-Null
        $tag = [Windows.Globalization.Language]::new($Sprache)
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($tag)
        if ($null -eq $engine) {
            Write-Error "Fuer $Sprache ist keine Texterkennung eingerichtet."
            exit 4
        }
    } else {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    }
    if ($null -eq $engine) {
        Write-Error "Keine Texterkennung eingerichtet. In den Windows-Einstellungen unter Zeit und Sprache eine Sprache hinzufuegen."
        exit 2
    }

    $ergebnis = Warte ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

    # Zeilenweise ausgeben statt als ein langer Klumpen: Im Chat gehoert
    # jede Zeile zu einer Nachricht, und beim Uebersetzen ist das die
    # natuerliche Einheit.
    foreach ($zeile in $ergebnis.Lines) {
        $zeile.Text
    }

} catch {
    Write-Error ("Texterkennung fehlgeschlagen: " + $_.Exception.Message)
    exit 3
}

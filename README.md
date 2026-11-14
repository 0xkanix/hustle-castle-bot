# Hustle Castle Bot

An open-source bot for the PC version of the game **Hustle Castle** (MY.GAMES launcher, Windows).
It watches the game window, recognises buttons and texts on the screen and clicks for you.
No memory reading, no game files touched — it only looks at the screen and moves the mouse.

> **Unofficial and at your own risk.** This project is not affiliated with MY.GAMES. Using a bot is
> against the rules of the game and can get an account banned.

## What it can do

- **Dungeon** and **Dark Cathedral** runs
- **Arena** tournaments (reads opponents, picks targets by rules you can tune)
- **Clan war** deployment (normal squad and titan squad)
- **Events** (hammer event, titan event), **titan tower**, **lighthouse**, **portal**, gem bay, clan shop
- A **control window** next to the game: start, pause, stop, emergency stop, settings, messages
- Optional **remote control through your own Discord bot** (commands like `!start arena 5`, `!stop`, `!help`)

## Safe to inspect

This repository contains the complete source code, so you can check what the bot does.

- **No licence keys, no copy protection, no obfuscation.**
- **No telemetry and no automatic updates.** The bot does not report anything to the author.
- The only network connections in the code:
  - **Discord** (`discord.com`, `gateway.discord.gg`) — only if you enter **your own** bot token and
    channel in `config.properties`. Leave them empty and the bot never goes online.
  - **localhost:8085** — optional temperature readings from a locally running LibreHardwareMonitor.
- An error report is only saved **locally** as a ZIP file (`logs\berichte`). Nothing is sent anywhere.

## Requirements

- Windows 10 or 11, Hustle Castle installed through the MY.GAMES launcher
- Java 21 (JDK) and Maven
- Game settings: language **English**, HD graphics **off**, VSync **off**, window mode (not full screen)

## Build and run

```
build.bat      (or: mvn clean package)
run.bat        (starts target\hustle-bot.jar)
```

`PC-Check.bat` checks your PC first and changes nothing.

Discord is optional. To use it, create your own Discord bot and enter the token and channel number in
`config.properties` (see [docs/GUIDE-1-SETUP-EN.txt](docs/GUIDE-1-SETUP-EN.txt)). Keep your token secret.

## Pictures ("templates")

The folder `templates` holds small cut-outs of game buttons and icons the bot looks for. If something is
not found on your screen, take your own with `capture.bat` (see
[docs/GUIDE-2-PICTURES-EN.txt](docs/GUIDE-2-PICTURES-EN.txt)).

## Text recognition

The bot reads text with the text recognition built into Windows. The source also supports PaddleOCR
models (`src/main/resources/paddleocr`), which are **not included** here because of their size;
without them the bot simply uses the Windows text recognition.

## Guides

| File | Topic |
|---|---|
| [docs/GUIDE-1-SETUP-EN.txt](docs/GUIDE-1-SETUP-EN.txt) | Set up the bot |
| [docs/GUIDE-2-PICTURES-EN.txt](docs/GUIDE-2-PICTURES-EN.txt) | Your own pictures |
| [docs/GUIDE-3-COMMANDS-EN.txt](docs/GUIDE-3-COMMANDS-EN.txt) | Commands |
| [docs/GUIDE-4-ARENA-EN.txt](docs/GUIDE-4-ARENA-EN.txt) | Arena |
| [docs/GUIDE-5-EVENT-EN.txt](docs/GUIDE-5-EVENT-EN.txt) | Events |
| [docs/EVENT-FLOW-EN.txt](docs/EVENT-FLOW-EN.txt) | The event loop in detail |
| [docs/CAPTURE-IMAGES-EN.txt](docs/CAPTURE-IMAGES-EN.txt) | Capturing pictures in detail |

## Project layout

```
src/main/java/de/hustlebot/
  bot/       game modes (dungeon, cathedral, arena, clan war, events ...)
  core/      screen capture, picture matching, text recognition, input, helpers
  steuer/    the control window
  discord/   optional remote control through your own Discord bot
  schicht/   overlay next to the game
  tools/     picture capture tool and other helpers
src/test/    unit tests
templates/   cut-outs of game buttons and icons
docs/        guides
```

The code comments and many file names are in German. "Autor" in comments stands for the original author.

## Deutsch (kurz)

Freier Bot für Hustle Castle am PC. Der komplette Quellcode liegt hier offen: keine Lizenzschlüssel, kein
Kopierschutz, keine Meldungen an den Autor, keine automatischen Updates. Ins Netz geht der Bot nur zu
Discord, und nur, wenn du deinen eigenen Bot-Token in `config.properties` einträgst. Bauen mit `build.bat`,
starten mit `run.bat`. Die Nutzung eines Bots verstößt gegen die Spielregeln — auf eigene Gefahr.

## License

[MIT](LICENSE) — free to use, change and share.

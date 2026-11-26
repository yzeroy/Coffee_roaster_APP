# Coffee_roaster_APP

Android aplikace pro tablet k pražičce kávy řízené deskou Portenta Machine Control. Náhrada programu Artisan a sériové konzole: živý graf, ovládání hořáku a měničů, PID a jeho kalibrace, poloautomatické receptury, záznamy pražení a diagnostika.

Aplikace funguje jen s firmwarem Portenty, který implementuje API kontrakt (TCP, JSON Lines, port 5020).

## Stav

**Demo verze ve větvi `test`.** Testovací varianta „Pražička TEST“ obsahuje vestavěný simulátor Portenty, takže jde vyzkoušet bez desky i bez firmwaru. Spojení se skutečnou Portentou je napsané podle `API.md` 1.7, ale zatím neověřené.

## Sestavení

- GitHub Actions sestaví APK při každém odeslání do `test` (testovací varianta) a `main` (provozní). APK je v záložce Actions u daného běhu, v části Artifacts.
- Lokálně: Android Studio, nebo `./gradlew assembleZkusebniDebug` / `assembleProvozniDebug` (JDK 17).
- Testy: `./gradlew testZkusebniDebugUnitTest` (simulátor, kontrola receptur, čtení stavu).

## Dokumentace

- [Specifikace aplikace](docs/specifikace.md)
- [Větve, varianty a vydávání](docs/vetve_a_vydavani.md)

## Větve

- `main` – stabilní verze pro tablet u pražičky
- `test` – vývoj a testovací varianta se simulací výpadků a poruch

## Instalace

Hotové verze budou ke stažení v sekci Releases. Aplikace si po instalaci sama kontroluje nové verze.

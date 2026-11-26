# Coffee_roaster_APP

Android aplikace pro tablet k pražičce kávy řízené deskou Portenta Machine Control. Náhrada programu Artisan a sériové konzole: živý graf, ovládání hořáku a měničů, PID a jeho kalibrace, poloautomatické receptury, záznamy pražení a diagnostika.

Aplikace funguje jen s firmwarem Portenty, který implementuje API kontrakt (TCP, JSON Lines, port 5020).

## Stav

Návrh – specifikace hotová, kód zatím není.

## Dokumentace

- [Specifikace aplikace](docs/specifikace.md)
- [Větve, varianty a vydávání](docs/vetve_a_vydavani.md)

## Větve

- `main` – stabilní verze pro tablet u pražičky
- `test` – vývoj a testovací varianta se simulací výpadků a poruch

## Instalace

Hotové verze budou ke stažení v sekci Releases. Aplikace si po instalaci sama kontroluje nové verze.

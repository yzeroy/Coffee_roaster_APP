# Větve a vydávání

## Větve

| Větev | Účel | Kdo z ní instaluje |
|---|---|---|
| `main` | stabilní verze, která běží v tabletu u pražičky | provozní tablet |
| `test` | nové funkce a opravy před nasazením, testovací varianta aplikace | testovací instalace (jiné zařízení nebo vedle provozní) |

### Pravidla

1. **Veškerý vývoj jde nejdřív do `test`.** Do `main` se nic necommituje přímo.
2. Když je verze v `test` vyzkoušená (proti simulaci ve firmwaru, případně na pražičce), **`test` se slučuje do `main`** a z `main` se vydá stabilní verze.
3. Opravu, která musí do tabletu hned, je možné udělat ve větvi z `main`, sloučit do `main` a **ihned i do `test`**, aby se větve nerozešly.
4. **Obě větve mají stejný kód.** Testovací nástroje (obrazovka Test, simulace) jsou v kódu obou větví, ale sestavují se jen do testovací varianty aplikace. Provozní varianta je neobsahuje vůbec.
   Důvod: kdyby testovací kód existoval jen ve větvi `test`, každé sloučení do `main` by ho muselo ručně vyřazovat a větve by se postupně rozcházely.
5. Na GitHubu doporučeno zapnout ochranu větve `main` (zákaz přímého push, změny jen přes pull request z `test`).

## Varianty aplikace

V Gradlu se varianty jmenují `provozni` a `zkusebni` (název varianty nesmí začínat na „test“). Úlohy: `assembleProvozniRelease`, `assembleZkusebniRelease`.

| | Provozní | Testovací |
|---|---|---|
| Sestavuje se z | `main` | `test` |
| Identifikátor aplikace | základní | základní + přípona `.test` (lze mít obě nainstalované najednou) |
| Název | Pražička | Pražička TEST |
| Testovací nástroje | ne | ano |

## Číslování verzí

`MAJOR.MINOR.PATCH`, např. `1.2.0`.

- MAJOR – změna verze protokolu (`proto`) nebo zásadní změna,
- MINOR – nové funkce,
- PATCH – opravy.

Testovací vydání mají příponu: `1.3.0-test.1`, `1.3.0-test.2`, …

## Vydání na GitHubu

- Stabilní vydání: tag `v1.2.0` na `main`, GitHub Release s podepsaným APK provozní varianty.
- Testovací vydání: tag `v1.3.0-test.1` na `test`, GitHub Release označené jako pre-release, s APK testovací varianty.
- Aplikace kontroluje jen vydání svého kanálu: provozní varianta ignoruje pre-release, testovací bere pouze pre-release.
- Repozitář je veřejný, takže aplikace kontroluje vydání bez přihlašování ke GitHubu.

## Podpisový klíč

- APK musí být vždy podepsané **stejným klíčem**, jinak Android aktualizaci odmítne (aplikaci by bylo nutné odinstalovat i s daty).
- Klíč (`.jks`) a jeho hesla **nikdy nepatří do repozitáře** (`.gitignore` je vylučuje). Uchovat zálohu na bezpečném místě mimo počítač.
- Pokud se vydávání bude dělat automaticky přes GitHub Actions, klíč se uloží do GitHub Secrets.

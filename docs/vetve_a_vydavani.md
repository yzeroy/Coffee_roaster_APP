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

## Vyzkoušení aktualizace z GitHubu

Předpoklad: podpisový klíč je v GitHub Secrets (`SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Bez něj má každé sestavení jiný ladicí klíč a Android aktualizaci odmítne („aplikace není kompatibilní“ / konflikt balíčku).

1. Odinstalovat z tabletu dosavadní demo (je podepsané jiným, ladicím klíčem). Jednorázově.
2. Na GitHubu: Releases → Draft a new release → tag `v0.1.0-test.1` (nový), cílová větev `test`, zaškrtnout **Set as a pre-release** → Publish. GitHub Actions sestaví testovací variantu a APK k vydání sám přidá (cca 5 min).
3. Na tabletu otevřít stránku Releases, stáhnout APK a nainstalovat (povolit „Instalovat neznámé aplikace“ pro prohlížeč).
4. Vytvořit další tag `v0.1.0-test.2` (stačí i bez změny kódu).
5. V aplikaci Nastavení → Aktualizace → „Zkontrolovat“ → nabídne `0.1.0-test.2` → Stáhnout a nainstalovat. Android napoprvé požádá o povolení „Instalovat neznámé aplikace“ pro Pražička TEST, pak instalaci potvrdit. Data aplikace (receptury, záznamy, nastavení) zůstanou.

Pozor: APK z běžného sestavení (artefakt z Actions, ne z vydání) má verzi `0.1.0-test.<číslo běhu>`. Kontrola aktualizací porovnává tato čísla s tagy, takže pro zkoušení aktualizací instalovat jen APK z vydání (Releases).

## Automatické testovací vydání

Commit do `test`, který má ve zprávě značku `[vydat]`, vydá GitHub Actions sám: po úspěšném sestavení vytvoří pre-release `v<verze>-test.<číslo běhu>` (např. `v0.1.0-test.31`) s podepsaným APK. Tablet ho najde přes Nastavení → Zkontrolovat teď. Ruční zakládání vydání na GitHubu tak není potřeba.

Číslo za `-test.` je číslo běhu GitHub Actions, takže vždy roste. Kdyby se vydání zakládalo i ručně, musí mít vyšší číslo než poslední automatické, jinak ho aplikace nebude brát jako novější.

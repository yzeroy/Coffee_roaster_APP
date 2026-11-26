# Specifikace aplikace – Pražička (Android tablet)

Verze: návrh 1, 26. 11. 2026

Aplikace nahrazuje Artisan a sériovou konzoli Portenty. Zobrazuje průběh pražení, ovládá pražičku přes Portentu, ladí PID a spravuje knihovnu receptur a záznamy pražení.

## 1. Zdroje pravdy

| Dokument | Kde | Co určuje |
|---|---|---|
| API (`API.md`) | repozitář firmwaru (hlavní kopie) a projekt „Prazicka“ v claude.ai | **jediné závazné rozhraní** s Portentou: zprávy, příkazy, povolené stavy, chyby |
| Stav projektu (`prazicka_stav_projektu.md`) | projekt „Prazicka“ | stavový automat, bezpečnostní pravidla, hardware |
| tato specifikace | `docs/` v tomto repozitáři | co aplikace dělá a jak vypadá |

Když se tato specifikace a API rozcházejí, platí API. Změna rozhraní = nejdřív změna `API.md` (řeší se v chatu firmwaru nebo společně), teprve potom aplikace.

## 2. Základní zásady

1. **Portenta rozhoduje, aplikace žádá.** Aplikace nikdy nepředpokládá, že příkaz proběhl – čeká na `ack` a skutečný stav bere jen ze zprávy `status`.
2. **Neplatná hodnota není nula.** `null` se zobrazuje jako „—“ a v grafu jako mezera, nikdy jako 0.
3. **Odmítnutí je vidět.** Každý `ack` s `ok:false` se ukáže obsluze srozumitelně (kód `err` přeložený do češtiny + `msg`). Varování (`event` s `level:"warn"`) se ukazují nevtíravě, chyby výrazně.
4. **Příkaz bez odpovědi** do 2 s se označí jako nepotvrzený a aplikace ho sama neopakuje (kromě `ping`).
5. **Kritické akce vyžadují potvrzení** dialogem nebo podržením tlačítka: `heat_start`, `recipe_start`, `restart`, uložení PID (`save:true`), `roast_event drop`. `heat_stop` naopak musí být vždy na jeden dotek: červené tlačítko **STOP ohřevu** dole v levé liště, viditelné na každé obrazovce a nezakryté žádným dialogem ani varováním. Vypne hořák a přeruší běžící recepturu (zůstane nahraná). Nejde o nouzové zastavení – to je hardwarové tlačítko STOP na pražičce, aplikace to nesmí naznačovat.
6. **Token** pro přihlášení k Portentě se zadává jen v nastavení aplikace a ukládá šifrovaně v zařízení. Nikdy není v kódu ani v repozitáři.
7. Rozhraní je **česky**, s diakritikou (texty z Portenty přicházejí bez diakritiky a zobrazují se tak, jak přišly).

## 3. Zařízení

- Android tablet, orientace na šířku. Návrh i pro menší tablety (cca 8″), ovládací prvky dost velké na dotyk v pracovním prostředí.
- Obrazovka během pražení nezhasíná.
- Tmavý i světlý motiv; výchozí tmavý (lépe čitelný graf).

## 4. Obrazovky

### 4.1 Pražení (hlavní obrazovka)

Rozdělená na tři části:

**Graf (většina plochy)**
- Osa X: čas od vsypání (před vsypáním záporný čas nebo čas od začátku záznamu).
- Křivky: teplota zrna `bt`, vzduch `air`, odtah `exh`, RoR zrna `bt_ror` (vlastní osa vpravo), cílová teplota `sp`, plyn `out`/`gas` (vlastní osa 0–100 %).
- Křivka receptury (`curve`) na pozadí, když je nahraná.
- Svislé značky událostí (`charge`, `dry_end`, `fc_start`, `fc_end`, `sc_start`, `drop`) a chyb.
- Viditelnost jednotlivých křivek lze zapínat a vypínat; přiblížení a posun gestem, tlačítko „zpět na živý průběh“.

**Panel hodnot (pruh nahoře nebo vpravo)**
- Velké číslo: teplota zrna a RoR. Menší: vzduch, odtah, plyn, cíl.
- Stav automatu česky (např. „Připraveno“, „Zapalování“, „Pražení“, „Chyba“), fáze receptury, čas od vsypání.
- Indikátory: spojení, plamen, interlock, STOP, PSU, simulace (když firmware hlásí `sim_vfd`/`sim_temp`).

**Ovládání (spodní pruh nebo boční panel)**
- `Spustit ruční pražení` / `Spustit recepturu`; zastavení tlačítkem STOP ohřevu v liště.
- **Teplotu vždy drží PID.** Hlavní ovládací prvek je **cílová teplota zrna** (velké číslo s tlačítky −/+ po 1 °C a 5 °C); změna se pošle `sp_set`.
- Posuvník plynu (10–100 %) je k dispozici vždy, ale **posunutí přepne na ruční plyn** (`gas_set` → režim `manual`). Aplikace to výrazně ukáže („Plyn řídíš ručně“) a nabídne velké tlačítko **„Řídit teplotou“** (`mode_set pid`), které vrátí řízení na cílovou teplotu plynule, bez skoku plynu.
- Režim se zobrazuje jako Teplota (PID) / Ručně / Receptura.

**Spuštění ručního pražení (dialog):**
- cílová teplota zrna (předvyplněná poslední použitá),
- **„Zaznamenat toto pražení?“ Ano / Ne** – výchozí volba podle Nastavení („Ukládat ruční pražení“),
- tlačítko **Spustit** pošle `heat_start` s `sp` (API 1.4), Portenta po zapálení sama přejde do PID na zadanou teplotu.
- Pokud firmware ještě neumí PID (`pid` chybí v `caps`), dialog cílovou teplotu nenabízí a upozorní, že pražení začne v ručním režimu.
- Měniče: buben, odtah, míchač, ventilátor – příkaz a skutečná rychlost (%), Hz, chyba měniče. Rychlé volby 0 / 10 / … / 100 %.
- Tlačítka událostí: Vsypání, Konec sušení, 1. crack start/konec, 2. crack, Vysypání.
- Opakované zapálení (`reignite`), když plamen zhasne za provozu.

**Vyzvání obsluhy**
- `charge_ready:true` → výrazná výzva „Předehřev hotový – vsyp zrno“ se zvukem.
- `drop_ready:true` → výzva „Připraveno k vysypání“ se zvukem.
- Výzvu lze zavřít; tlačítko akce zůstává zvýrazněné.

**Chybový stav (HALTED)**
- Celoobrazovkový pruh s kódem a příčinou česky (tabulka kódů z API, neznámý kód = „neznámá chyba (kód N)“), měnič, kterého se chyba týká, čas vzniku chyby a popis z Portenty (`fault.msg`).
- Tlačítko `Restart` (jen když STOP i PSU jsou v pořádku, jinak vysvětlení, co je potřeba).
- Po restartu aplikace upozorní, že ohřev je blokovaný, dokud ho obsluha nezruší (`heat_blocked`).

### 4.2 Receptury

- Knihovna: seznam, hledání, duplikace, smazání, import/export souboru.
- Editor:
  - název, `charge_bt`, `charge_gas`, `pid_delay_s`,
  - křivka teploty zrna – tabulka bodů i úprava tažením bodů v grafu,
  - rozpis měničů po časech (tabulka),
  - podmínka vysypání (`drop.bt`, `drop.t_max`), chlazení (`cool`).
- Aplikace recepturu zkontroluje **stejnými pravidly jako firmware** (rozsahy, počty bodů, rostoucí časy, buben a odtah min. 10 %) ještě před odesláním; konečné slovo má Portenta (`bad_recipe`).
- „Vytvořit recepturu ze záznamu“: z uloženého pražení se nabídne křivka `bt` a rozpis měničů jako výchozí návrh.
- Nahrání do Portenty (`recipe_load`) a start (`recipe_start`) z hlavní obrazovky nebo z knihovny.

### 4.3 Kalibrace PID

- Zobrazení aktuálních konstant (`pid_get`) a příznaku „uloženo trvale“.
- Ruční ladění: pole `kp`, `ki`, `kd`, tlačítka „Vyzkoušet“ (`save:false`) a „Uložit trvale“ (`save:true`, s potvrzením; Portenta přijme uložení nejvýš 1× za 10 s, tlačítko je mezitím neaktivní). Živý graf `bt`, `sp`, `out`.
- Automatické ladění: zadání `sp`, `gas_low`, `gas_high`, `cycles`; průběh (fáze, cyklus x z y, uplynulý čas); tlačítko „Přerušit“.
- Výsledek ladění: navržené konstanty vedle současných, možnost upravit a teprve pak použít. Nic se nepoužije automaticky.
- Upozornění před laděním na skutečné pražičce: dokud firmware nemá limity přehřátí (etapa 3), aplikace zobrazí varování.

### 4.4 Záznamy pražení

- Historie: datum, receptura, délka, teplota vysypání, poznámka, hodnocení.
- Detail: graf jako na hlavní obrazovce, tabulka událostí, poznámky (zrno, šarže, hmotnost před/po, úbytek %).
- Porovnání dvou záznamů v jednom grafu.
- Export a import (viz bod 6).

### 4.5 Diagnostika

- Výstup `get_diag`: verze firmwaru, sestavení, doba běhu, důvod posledního startu (`reset_reason` – výrazně upozornit na `watchdog` a `brown_out`), IP, MAC, simulace, počty přihlášení, panik a odmítnutých přihlášení, poslední chyby měničů, PID, konfigurace.
- Log událostí (`event`) s filtrem podle úrovně a kódu, kopírování a export.
- Stav spojení: zpoždění odpovědí, počet výpadků od spuštění.
- Náhrada sériové konzole: všechny funkce, které dnes nabízí konzole (`s`, `ip`, `r`, simulace), jsou dostupné tady nebo na hlavní obrazovce.

### 4.6 Nastavení

- Adresa Portenty a port (výchozí 5020) – tlačítko „Najít Portentu“ (UDP dotaz podle API bod 2.3), jinak ruční zadání IP. Nalezená adresa se uloží.
- Token (8–32 znaků ASCII bez mezer, aplikace kontroluje už při zadání), test spojení.
- Zvuky výzev, motiv, jednotky času v grafu, výchozí viditelnost křivek.
- „Ukládat ruční pražení“ – výchozí odpověď v dialogu před ručním pražením.
- Aktualizace aplikace (bod 7).
- O aplikaci: verze aplikace, varianta (provozní / testovací), verze protokolu.

## 5. Spojení

Podle API (body 4 a 5):

- Po spuštění se aplikace připojí, pošle `hello`; při `bad_token` nebo `proto` přestane zkoušet a zobrazí důvod (u `proto`: je potřeba aktualizovat aplikaci nebo firmware).
- `ping` každou 1 s, když se jinak nic neposílá.
- Bez `status` 3 s → spojení považováno za ztracené, nové pokusy každé 2 s.
- **Ztráta spojení za ohřevu** (poslední známý stav `IGNITION_*` nebo `RUNNING`): přes celou obrazovku varování s odpočtem do 15 s od posledního úspěšného spojení – „Portenta za X s vypne hořák a přejde do chyby“. Po obnovení se varování zavře a aplikace ukáže skutečný stav.
- Portenta přijímá jen jednoho klienta. Připojí-li se jiné zařízení, staré spojení se zavře – aplikace pak ukáže „Spojení převzalo jiné zařízení“ a sama se znovu nepřipojuje, dokud to obsluha nepotvrdí (aby se dvě zařízení nepřetahovala).
- Aplikace běží jako služba v popředí během ohřevu, aby ji systém neuspal a spojení nespadlo při zhasnutí obrazovky nebo přepnutí aplikace.

## 6. Záznam pražení

- Záznam začíná `recipe_start` (vždy) nebo `heat_start` (jen když obsluha v dialogu zvolila „Zaznamenat“) a končí `recipe_end`, návratem do klidu po `heat_stop`, nebo chybou.
- Během ručního pražení bez záznamu jde záznam ještě zapnout tlačítkem „Začít zaznamenávat“ (od toho okamžiku).
- Ukládá se každá zpráva `status` (2 Hz) a každý `event`. Výpadky spojení zůstanou v záznamu jako mezery a jsou v grafu vyznačené.
- Ukládání průběžně během pražení (při pádu aplikace se záznam neztratí).
- Formát: vlastní, verzovaný (obsahuje verzi aplikace, firmwaru a protokolu). Export do CSV. **Export do formátu Artisanu (.alog)** – k ověření, zda je proveditelný, užitečný pro porovnání se staršími pražením.
- Záloha: export celé knihovny (receptury + záznamy) do jednoho souboru.

## 7. Aktualizace aplikace

- Vydání přes **GitHub Releases** tohoto repozitáře (podrobně `docs/vetve_a_vydavani.md`).
- Aplikace při spuštění (a z nastavení ručně) zkontroluje nejnovější vydání svého kanálu, nabídne stažení a instalaci.
- Android instalaci aplikace z jiného zdroje než obchodu vždy nechá potvrdit uživatelem (jednorázové povolení „instalovat neznámé aplikace“ + potvrzení každé instalace). Úplně tichá instalace se neplánuje.
- **Za ohřevu se aktualizace nenabízí ani neinstaluje.**

## 8. Testovací varianta

Aplikace existuje ve dvou variantách, které se dají nainstalovat vedle sebe:

| | Provozní | Testovací |
|---|---|---|
| Kde vzniká | větev `main` | větev `test` |
| Název na tabletu | Pražička | Pražička TEST |
| Barevné odlišení | standardní | výrazný pruh „TEST“ na všech obrazovkách |
| Kanál aktualizací | jen stabilní vydání | testovací vydání |
| Testovací nástroje | nejsou | obrazovka Test |

**Obrazovka Test** (jen testovací varianta):

- **Simulace na straně firmwaru** – příkazy `sim_flame`, `sim_temp`, `sim_vfd`, `sim_di`, `sim_reset` z API bodu 12 (plamen nechytí, teploty včetně neplatného čtení, poruchy měničů, simulovaná ztráta STOP/PSU) a přehled aktivních poruch ze `status.sim`.
- **Simulace na straně aplikace**:
  - výpadek spojení: aplikace přestane posílat `ping` a příkazy na zvolenou dobu (např. 5 s – obnoví se v okně, 20 s – panika),
  - úplné odpojení a znovupřipojení,
  - zpožděné odpovědi.
- Ruční odeslání libovolné zprávy JSON a přehled surového provozu (pro ladění kontraktu).

**Ochrana před použitím na skutečné pražičce:** testovací nástroje, které ovlivňují pražičku (simulace výpadku spojení, `sim_*`), jsou povolené **jen když Portenta v `hello_ack` hlásí `sim_vfd:true` a `sim_temp:true`**. Proti firmwaru bez simulace funguje testovací varianta jako provozní, jen s pruhem „TEST“.

Aplikace v `hello` posílá `"variant":"release"` nebo `"variant":"test"` (API bod 4.1). Firmware variantu jen zaznamená do logu a `diag`, žádná práva navíc nedává.

## 9. Otevřené body

- Technologie: navrženo **Kotlin + Jetpack Compose**, knihovna pro živý graf k výběru (musí zvládnout 2 Hz × několik křivek × 20 min plynule).
- Export do `.alog` – proveditelnost.
- Úložiště receptur a záznamů jen v tabletu, nebo i záloha mimo něj (např. do složky ve sdíleném úložišti).
- Minimální verze Androidu podle konkrétního tabletu.

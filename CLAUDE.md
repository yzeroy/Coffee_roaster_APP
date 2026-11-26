# Pravidla pro práci v tomto repozitáři

Android aplikace pro tablet, která ovládá pražičku kávy přes Portenta Machine Control. Nahrazuje Artisan a sériovou konzoli.

## Zdroje pravdy

- **API** `API.md` je jediné závazné rozhraní s firmwarem. Hlavní kopie je v kořeni repozitáře firmwaru (https://github.com/yzeroy/Coffee_roaster_firmware), pracovní kopie `claude/API.md` v claude.ai projektu „Prazicka“. Před prací na komunikaci ho vždy přečti, ne z paměti.
- Stav projektu `claude/prazicka_stav_projektu.md` (projekt „Prazicka“) – stavový automat a bezpečnostní pravidla.
- `docs/specifikace.md` – co aplikace dělá. `docs/vetve_a_vydavani.md` – větve, varianty, vydávání.
- Firmware se vyvíjí v samostatném chatu a repozitáři. Rozhraní neměň jednostranně: když aplikace potřebuje změnu, navrhni nejdřív úpravu `API.md` (obě kopie) a nech ji schválit.

## Zásady

- Portenta rozhoduje, aplikace žádá. Aplikace nesmí obcházet bezpečnostní pravidla ani předpokládat provedení příkazu bez `ack` a `status`.
- `null` z Portenty se nikdy nezobrazuje ani neukládá jako 0.
- Token k Portentě nikdy nesmí být v kódu, testech, ukázkách ani v repozitáři. Zadává se jen v nastavení aplikace.
- Podpisové klíče a hesla nepatří do repozitáře.
- Testovací nástroje se sestavují jen do testovací varianty; nástroje ovlivňující pražičku smí fungovat jen proti firmwaru se zapnutou simulací (`sim_vfd` a `sim_temp` v `hello_ack`).

## Větve

- Vývoj jde do `test`. Do `main` jen sloučením z `test` (nebo neodkladnou opravou, která se hned sloučí i do `test`).
- Obě větve mají stejný kód; liší se jen tím, ze které se sestavuje která varianta.

## Jazyk

- Dokumentace, texty v aplikaci a zprávy commitů česky.

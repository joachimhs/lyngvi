# Lyngvi 2 — spesifikasjon for en generell implementasjon

> **Formål.** Dette dokumentet beskriver en ny, generell implementasjon av
> Lyngvi. Det bygger på kravspesifikasjonen for versjon 1.1.2
> (`agentsandbox-kravspesifikasjon.md`, heretter «1.1.2-spesifikasjonen»),
> beholder kjernepremissene og de tverrgående kontraktene derfra, og
> generaliserer alt som i dag er bundet til én organisasjon, én agent, én
> verktøykjede og ett språk.
>
> Dokumentet er skrevet slik at det kan brukes som eneste grunnlag for
> implementasjon. Der det refererer til 1.1.2-spesifikasjonen, er det for å
> peke på hva som er uendret, ikke for å delegere krav dit.
>
> **Status:** utkast til gjennomgang. Versjon 2.0.0 er målversjonen.

---

## 0. Beslutninger dokumentet bygger på

| # | Beslutning | Begrunnelse |
|---|---|---|
| B1 | **Én Java-kjerne, to front-ender.** All logikk (samtaler, uttak, generering, stakk, adaptere) ligger i modulen `lyngvi-core` (Java 25). GUI-et kaller kjernen i samme prosess. `setup.sh` starter `lyngvi-cli`, som er et tynt `main` rundt samme kjerne. | I 1.1.2 implementerer Bash og Java samme flyt hver for seg, og paritet må testes. Med én kjerne er paritet gitt, og de vanskelige delene (patch-klassifisering, strømming med avbrudd, låsing, JSON) skrives i et språk som egner seg. |
| B2 | **JavaFX beholdes** som GUI-teknologi. | Eksisterende kompetanse, eksisterende visningskomponenter (markdown, diff, editor, arbeidslogg) kan gjenbrukes nesten uendret. |
| B3 | **`setup.sh` er et skall uten logikk.** Det finner en kjøretid og starter CLI-en; alt annet skjer i kjernen. CLI-en har `--json` på alle kommandoer, og den kontrakten (kapittel 4) er det offentlige, språkuavhengige grensesnittet for scripting og tester. | Bestiller vil beholde `setup.sh` som inngang. Logikken bør likevel bo ett sted. |
| B4 | **Én container per samtale.** | Lukker den stille feilen der én agent kan redigere en annen samtales arbeidskopi (1.1.2 kap. 4.6), og gjør ressursgrenser per samtale meningsfulle. |
| B5 | **Agenten er utskiftbar.** Referanseadaptere: **Claude Code** og **OpenCode**, begge med abonnementsinnlogging. Copilot CLI er en valgfri tredje adapter. | Kravet fra bestiller. Abonnement (OAuth) må kunne brukes, ikke bare API-token. |
| B6 | **Agentens egen legitimasjon ligger i containeren.** Dette er en kjent og dokumentert begrensning, ikke noe denne versjonen lukker. | Bestillers valg. Token-injeksjon via gatewayen for agentens egne API-kall er designet inn som fremtidig arbeid (kap. 11). |
| B7 | **Plattform: macOS og Linux. Runtime: Podman.** Docker og Windows/WSL2 er utenfor scope. | Bestillers valg. Holder nettverks- og UID-håndtering håndterbar. |
| B8 | **Organisasjonsprofil i stedet for hardkoding.** Alt som i 1.1.2 er spesifikt for én organisasjon (vertsnavn, enterprise-URL, artefaktspeil, CA-navn, allowlister) flyttes til `profile.json` og en medfølgende profilpakke. Kildekoden inneholder ingen referanser til noen bestemt organisasjon eller dens interne systemer. | Kjernen i «generell». |
| B9 | **Engelsk som standardspråk, norsk som fullverdig oversettelse.** | Et generelt produkt kan ikke være norsk-only. All brukervendt tekst går via ressursfiler. |
| B10 | **Ingen Java-installasjon kreves på hosten.** CLI-en distribueres som et jlink-bilde med innebygd kjøretid per plattform; GUI-appen pakkes med jpackage og deler samme kjøretid. Verken `jq`, `node` eller `bash ≥ 5` er vertsavhengigheter. | En jar kan ikke inneholde sin egen kjøretid, men et jlink-bilde kan. Kompakte kildefiler (`java Main.java`) brukes ikke i produksjon: de rekompilerer ved hver kjøring og krever et JDK. |
| B11 | **Adapterkontrakten er prosessbasert og språkuavhengig**, men referanseadapterne er skrevet i Java inne i kjernen. | Tredjeparter kan skrive en adapter i hva som helst. Referanseadapterne får typet kode og tester. |
| B12 | **Gatewayen er Java**, med plugins som jar-filer lastet via `ServiceLoader`. Kontraktmodulen `lyngvi-gateway-api` er ren JDK og deles mellom kjernen på hosten og gatewayen i containeren. | Ett språk i hele produktet, delte typer for manifest og allowlister, og utvidelse ved å legge en jar i `gateway/plugins/`. Node forsvinner fra hosten og gatewayen. |

---

## 1. Kjernepremisser (uendret fra 1.1.2)

Disse fire premissene er produktet. Alt annet er skall.

1. **Ingen direkte nettverkstilgang.** Agentcontaineren er kun på et internt
   nett uten default route. All utgående trafikk går gjennom en Squid-proxy
   med allowlist (ikke blocklist), pluss en eksplisitt blocklist over kjente
   eksfiltrasjonsendepunkter.
2. **Ingen tilgang til hostens git-repo.** Agenten jobber i en filkopi uten
   hostens `.git`. Hele git-angrepsflaten (hooks, `core.fsmonitor`, alias,
   remotes med legitimasjon) fjernes strukturelt.
3. **Hemmeligheter mot interne systemer bor i en gateway-sidecar.** Agenten
   når intranettet kun gjennom et read-only API med allowlist, og ser aldri
   en PAT.
4. **Validert uttak med menneskelig godkjenning.** Endringer kommer tilbake
   som en patch som valideres på hosten, fil for fil, i tre bøtter
   (anvendes, holdt tilbake, avvist), og landes alltid på en arbeidsgren.

De tverrgående kontraktene i 1.1.2-spesifikasjonen kapittel 4 (samtale,
arbeidskopi, uttak), 5 (sikkerhetsmodell) og 6 (ressursgrenser) videreføres
med de endringene kapittel 5 og 7 i dette dokumentet angir. Ved motstrid
gjelder dette dokumentet.

### 1.1 Hva som generaliseres

| Bundet i 1.1.2 | Generelt i 2.0 |
|---|---|
| GitHub Copilot CLI | Agentadapter-kontrakt; Claude Code og OpenCode som referanse |
| Én organisasjons vertsnavn, enterprise-URL, artefaktspeil og CA-navn hardkodet i skript, compose, Squid, gateway og GUI | `profile.json` + profilpakke med allowlister, gateway-manifest og gateway-plugins |
| Tre JDK-er, Kotlin, Maven bakt inn i imaget | Slankt basisimage + valgbare verktøykjede-fragmenter |
| Norsk UI og norske CLI-tekster i tester | Ressursfiler; engelsk standard, norsk inkludert |
| Mac + Podman machine | macOS og Linux; runtime-forskjeller isolert i én modul |
| Delt container for alle samtaler | Én container per samtale |
| Gateway med to innebygde adaptere (Confluence, Jira) | Generisk gatewaykjerne med plugin-system; ingen systemer innebygd |
| `setup.sh` og Java implementerer samme flyt | Én Java-kjerne; `setup.sh`/CLI og GUI er front-ender |
| `sandbox.config` i flere formater, versjon tre steder | Ett skjema per fil, én `VERSION`-fil |

---

## 2. Systemarkitektur

```
┌────────────────────────────────────────────────────────────────────────┐
│  Host (macOS / Linux)                                                  │
│                                                                        │
│   JavaFX GUI ─────┐                     terminalbruker                 │
│   (jpackage)      │                          │                         │
│                   │ in-process               ▼                         │
│                   │                     setup.sh  (launcher)           │
│                   │                          │ exec                    │
│                   │                          ▼                         │
│                   │                  lyngvi-cli (jlink-bilde)    │
│                   │                          │ in-process              │
│                   ▼                          ▼                         │
│             ┌──────────────────────────────────────────┐               │
│             │  lyngvi-core (Java 25)             │               │
│             │  eier all tilstand, git- og podman-kall  │               │
│             └───────┬──────────────────────┬───────────┘               │
│                     │ leser/skriver        │ podman                    │
│                     ▼                      ▼                           │
│   state/  .sessions/         ┌──────────────────────────────────┐      │
│                              │  Felles stakk (podman compose)   │      │
│                              │   proxy (Squid)   gateway (Java) │      │
│                              │   <prefix>.5      <prefix>.10    │      │
│                              └──────┬───────────────┬───────────┘      │
│                                     │ internt nett  │                  │
│         ┌───────────────────────────┴───────────────┴───────────┐      │
│         │  Én agentcontainer per samtale (podman run)            │      │
│         │   /work/<repo>  /work/output  /work/AGENTS.md          │      │
│         └────────────────────────────────────────────────────────┘      │
└────────────────────────────────────────────────────────────────────────┘
                     proxy ──► internett (allowlist)
                     gateway ──► intranett (hemmeligheter)
```

### 2.1 Delsystemer

| Del | Delsystem | Teknologi | Kapittel |
|---|---|---|---|
| A | `lyngvi-core` — kjernen: samtaler, uttak, generering, stakk, adaptere, konfig | Java 25, én tredjepartsavhengighet (JSON) | 4, 5 |
| A' | `lyngvi-cli` + `setup.sh` — kommandolinje-front-end og launcher | Java 25 `main` + POSIX `sh` | 4 |
| B | Agentadaptere | `agent.json` + Java-klasse i kjernen eller ekstern `adapter.sh` | 6 |
| C | Kjøremiljø: images, stakk, per-samtale container, Squid | Containerfile-fragmenter, compose, squid.conf | 7 |
| D | Gateway: generisk kjøretid + plugins som jar-filer | Java 25 (`jdk.httpserver`, `java.net.http`), Jackson; kontraktmodul uten avhengigheter | 8 |
| E | JavaFX GUI | Java 25 + JavaFX + FXML, avhenger av A | 9 |
| F | Profilpakke | JSON + tekstfiler | 3 |

### 2.2 Én kjerne, to front-ender

Bestiller åpnet for at GUI-et kan bruke `setup.sh` for utførelse, og
deretter for at `setup.sh` kan være et skall rundt Java. Det siste er
valgt, av fire grunner:

1. **Én kilde til sannhet for en sikkerhetskritisk flyt.** Uttaksflyten
   (baseline, diff, tre bøtter, arbeidsgren, stash) er den delen som *ikke*
   må avvike mellom CLI og GUI. I 1.1.2 er den implementert to ganger, og
   1.1.2-spesifikasjonens Del D beskriver Java-versjonen på en måte som
   motsier kontrakten på flere punkter. Med én kjerne forsvinner den
   feilklassen, og GUI-et slipper å parse JSON fra en prosess.
2. **Riktig språk for det som er vanskelig.** Diff-parsing, JSON, låsing,
   strømming med avbrudd, feilhåndtering og stier på tvers av plattformer
   gjør Bash dårlig og Java bra. 4 600 linjer Bash var den største
   vedlikeholdsrisikoen i 1.1.2.
3. **Git og Podman er skallverktøy uansett.** Kjernen kaller dem med
   `ProcessBuilder`, slik 1.1.2-GUI-et allerede gjør. Ingenting går tapt.
4. **Testbarhet.** Kjernen testes med JUnit uten JavaFX og uten Podman
   (mock-adapter, midlertidige git-repoer). CLI-kontrakten testes
   ende-til-ende i CI.

Prisen er at CLI-en trenger en Java-kjøretid. Den følger med (kap. 4.6),
så brukeren installerer ikke Java.

`setup.sh --json` beholdes som offentlig kontrakt for scripting, andre
verktøy og tester. GUI-et bruker den ikke; det kaller kjernens API direkte.
Begge front-ender er tynne: CLI-en oversetter argv til kjernekall og
skriver JSON, GUI-et oversetter knappetrykk til kjernekall og viser
resultatet.

Det GUI-et gjør utenom kjernen:
- lese filer for visning (arbeidskopi i filpanelet, logger), aldri skrive
  tilstand;
- vise, redigere og lagre filer i arbeidskopien via filpanelet (dette er
  brukerens egen redigering på hosten, ikke en tilstandsendring i
  kontrakten);
- egne innstillinger (`~/.lyngvi-gui.properties`).

### 2.3 Prosessgrenser og tillit

| Grense | Tillit |
|---|---|
| Host ↔ agentcontainer | Containeren er ikke betrodd. Alt den produserer (patch, output-filer, hendelsesstrøm) valideres eller behandles som data. |
| Host ↔ gateway | Betrodd komponent som eier hemmeligheter. Kjører read-only, non-root, `cap_drop: ALL`. |
| GUI/CLI ↔ kjerne | Samme prosess, samme bruker. Kjernen validerer alle argumenter uansett hvilken front-end som kaller. |
| Kjerne ↔ ekstern adapter | Adapteren er en del av installasjonen (ikke fra containeren) og er betrodd. Output fra agenten som passerer gjennom adapteren er ikke betrodd. |

---

## 3. Konfigurasjonsmodell

All konfigurasjon ligger i sandkassemappen. Ingen fil har mer enn ett
format, og hvert format har et skjema under `contract/`.

```
<sandkasse>/
├── VERSION                      # én linje, semver; eneste versjonskilde
├── profile.json                 # organisasjonsprofil (kap. 3.1)
├── sandbox.config               # lokal tilstand, key=value (kap. 3.2)
├── .env                         # build-args og lokale overstyringer
├── agents/<id>/                 # agentadaptere (kap. 6)
│   ├── agent.json
│   ├── adapter.sh
│   └── image.Containerfile      # fragment som legges på basisimaget
├── image/
│   ├── base.Containerfile       # basisimage
│   └── fragments/<name>.Containerfile
├── proxy/
│   ├── squid.conf
│   ├── allowed-domains.txt      # generert fra profil + agent + brukerens tillegg
│   ├── blocked-domains.txt
│   └── gateway-hosts.txt        # generert fra manifest
├── gateway/                     # kap. 8
│   ├── core/                    # generisk kjerne (ingen systemkunnskap)
│   ├── plugins/*.jar            # plugin-jar-er (ServiceLoader), kap. 8.6
│   ├── gateway.manifest.json    # instanser (konfigurerte systemer)
│   ├── allowlists/<instans>.txt
│   ├── secrets/<instans>.env    # gitignorert; .env.example genereres
│   └── certs/
├── skills/                      # ferdigheter sandkassen gir agenten (kap. 3.6)
│   ├── skills.json              #   av/på per skill
│   └── <navn>/SKILL.md          #   én mappe per skill, med støttefiler
├── cli/                         # jlink-bilde: kjøretid + core + cli (gitignorert)
│   ├── bin/lyngvi
│   └── lib/
├── contract/                    # JSON-skjemaer for all --json-output (kopiert fra jaren ved init)
├── state/
│   ├── conversations/<id>.json  # samtalefiler, delt GUI/CLI
│   ├── agents/<agent-id>/       # agentens hjemmekatalog (legitimasjon, cache)
│   └── subnets.txt              # flyttet fra ~/.cache; se kap. 7.5
├── .sessions/<id>/              # host-siden av en samtale: miljøfil, vedlegg,
│                                #   patcher (arbeidskopiene ligger i et volum, kap. 5)
├── Containerfile                # generert
├── docker-compose.yml           # generert
└── AGENTS.md                    # generert fra mal + profil
```

Gitignorert: `sandbox.config`, `.env`, `Containerfile`, `docker-compose.yml`,
`state/`, `.sessions/`, `cli/`, `proxy/allowed-domains.txt`,
`proxy/gateway-hosts.txt`, `gateway/secrets/*.env`, `gateway/certs/*`,
`AGENTS.md`.

### 3.1 `profile.json` — organisasjonsprofil

Profilen er det som gjør en installasjon til én bestemt organisasjons
oppsett. Den leveres som en **profilpakke**: en katalog eller et git-repo
med `profile.json`, `gateway/gateway.manifest.json`, `gateway/allowlists/`,
`gateway/secrets/*.env.example`, eventuelt `gateway/plugins/*.jar` (kap. 8.6)
og eventuelt `AGENTS.md.d/*.md` (tillegg til agentinstruksjonene).

```json
{
  "schemaVersion": 1,
  "name": "acme",
  "label": "Acme Software",
  "language": "en",
  "agents": {
    "default": "claude-code",
    "enabled": ["claude-code", "opencode"]
  },
  "toolchains": ["node22", "java21", "python312"],
  "egress": {
    "allow": [
      "registry.npmjs.org",
      "repo1.maven.org",
      ".pypi.org",
      "files.pythonhosted.org",
      "github.com",
      "api.github.com",
      "codeload.github.com",
      "objects.githubusercontent.com",
      "raw.githubusercontent.com"
    ],
    "block": "bundled",
    "blockExtra": [".discord.com"]
  },
  "mirrors": {
    "npm": "https://nexus.acme.example/repository/npm/",
    "maven": "https://nexus.acme.example/repository/maven/",
    "pypi": "https://nexus.acme.example/repository/pypi/simple"
  },
  "ca": {
    "certsDir": "~/.cache/lyngvi/ca",
    "macKeychainNames": ["Acme Root CA"]
  },
  "gateway": {
    "manifest": "gateway/gateway.manifest.json",
    "plugins": "gateway/plugins",
    "allowWrite": []
  },
  "git": {
    "protectedBranches": ["main", "master", "develop"],
    "branchPrefix": "agent/",
    "committer": { "name": "lyngvi", "email": "lyngvi@local" }
  },
  "resources": { "mem": "8g", "memswap": "12g", "cpus": "4.0", "pids": "2048" },
  "resourcePresets": [
    { "id": "review", "limits": { "mem": "2g",  "memswap": "3g",  "cpus": "1.0", "pids": "1024" } },
    { "id": "dev",    "limits": { "mem": "8g",  "memswap": "12g", "cpus": "4.0", "pids": "2048" } },
    { "id": "build",  "limits": { "mem": "16g", "memswap": "24g", "cpus": "6.0", "pids": "4096" } }
  ],
  "copy": {
    "history": "shallow",
    "depth": 100,
    "excludes": ["node_modules", "build", "target", "dist",
                 ".gradle", ".idea", ".venv", "venv", "__pycache__",
                 ".next", "out", ".pytest_cache", "*.egg-info"],
    "maxFileMiB": 50
  },
  "session": {
    "idleStopMinutes": 60,
    "idleRemoveHours": 72
  },
  "cache": {
    "mode": "shared",
    "paths": ["npm", "pip", "go-mod", "go-build"]
  }
}
```

Regler:

- `egress.block: "bundled"` betyr den medfølgende blocklisten (1.1.2 Del B
  §3.4, uendret). En liste erstatter den. `blockExtra` legger til.
- `egress.allow` er profilens grunnliste. `proxy/allowed-domains.txt`
  genereres som: profilens liste + de aktiverte agentenes påkrevde domener
  (kap. 6.3) + brukerens egne tillegg (bevart i en egen seksjon av filen,
  se kap. 4.5). Generering skriver in-place (inode bevares, filen er
  single-file-mountet).
- `mirrors` skrives inn i containerens `~/.npmrc`, `~/.m2/settings.xml` og
  `~/.config/pip/pip.conf` av entrypointet. Tomme verdier gir ingen fil.
- `toolchains` velger fragmenter fra `image/fragments/`. Ukjent navn gir
  feil ved `rebuild-config`.
- `copy.history` styrer hvor mye git-historikk arbeidskopien får (kap. 5.2):
  - `none`: ingen historikk. Kopien får et nytt `.git` med én baseline-commit
    (oppførselen fra 1.1.2).
  - `shallow` (standard): en sanitert klon av gjeldende gren, begrenset til
    `copy.depth` commits (standard 100).
  - `full`: sanitert klon med hele historikken til gjeldende gren.
  Uansett verdi kopieres aldri hooks, konfig eller remotes fra hosten.
  Historikk kan inneholde hemmeligheter som er fjernet i HEAD; profilen
  bør sette `none` for repoer der det er en risiko, og `sandbox.config`
  kan overstyre per repo (kap. 3.2).
- `copy.excludes` gjelder arbeidstreet. `.git` er alltid ekskludert fra
  rsync-steget og trenger ikke stå i listen.
- `resources` er standardverdier; `sandbox.config` og `session new --mem`
  overstyrer.
- `resourcePresets` er navngitte sett av alle fire grensene, ett per slags
  arbeid (`review`, `dev`, `build` i standardprofilen). Utelates feltet, gjelder
  de innebygde. `--preset <id>` i `resources set` og `session new` setter hele
  settet; enkeltvalg som `--mem` overstyrer det. I tillegg utledes ett forvalg
  av maskinen selv (tre fjerdedeler av minnet og alle kjerner).
- `gateway.allowWrite` lister gateway-instanser som får bruke andre
  metoder enn GET (kap. 8.4). Tom som standard.
- Standardprofilen `profiles/default.json` har tom `mirrors`, ingen
  gateway-instanser, `toolchains: ["node22"]` og en minimal allowlist
  (kun npm-registeret). Den er nok til å kjøre en agent mot et Node-prosjekt.

### 3.2 `sandbox.config` — lokal tilstand

Key=value, én per linje, `#` er kommentar. Dette er det *eneste* formatet.
Skrives kun av `setup.sh` (atomisk: temp + `cat >` in-place).

```
# Generated by setup.sh; edit with 'setup.sh repo|resources|agent'.
SCHEMA_VERSION=2
UID=502
GID=20
PROFILE=/path/to/profile-pack
SUBNET_PREFIX=172.31.240
MEM_LIMIT=8g
MEMSWAP_LIMIT=12g
CPUS=4.0
PIDS_LIMIT=2048
DEFAULT_AGENT=claude-code
LANG_UI=en
REPO=/Users/alice/src/api|api
REPO=/Users/alice/src/web
REPO=/Users/alice/src/legacy-billing|billing|history=none
```

- `REPO=<sti>[|<navn>[|history=<none|shallow|full>]]`. Det tredje feltet
  overstyrer profilens `copy.history` for dette repoet.
  Navn må matche `^[A-Za-z0-9._-]+$` og kan ikke
  være `output`, `_attachments`, `AGENTS.md` eller `CLAUDE.md` (reserverte
  navn i `/work`).
- `SUBNET_PREFIX` tildeles ved `init` (kap. 7.5).
- `UID`/`GID` settes ved `init`; `doctor` feiler hvis de ikke matcher
  `id -u`/`id -g`.

### 3.3 `.env`

Som 1.1.2: build-args (`NODE_VERSION`, `JAVA_VERSION`, …), ressursgrenser for
proxy og gateway, og valgfrie overstyringer. Eneste endring: ingen
agent-token her. Legitimasjon for agenter håndteres av adapteren (kap. 6.4).

### 3.4 `VERSION`

Én fil, én linje. `setup.sh version`, GUI-ets versjonsknapp og
`sandbox.version`-stempelet i en installert sandkasse leser alle herfra.
`CHANGELOG.md` har formatet `## <versjon> - <ÅÅÅÅ-MM-DD>` med underoverskrifter
`### Added | Changed | Fixed` (engelsk i filen; GUI oversetter overskriftene).

### 3.5 Språk

All brukervendt tekst ligger i `ResourceBundle`-filer i kjernen
(`messages_en.properties`, `messages_nb.properties`) og brukes av både CLI
og GUI. Manglende nøkkel i valgt språk faller tilbake til `en`; CI feiler
hvis `nb` mangler nøkler som finnes i `en`. Ingen tekst som vises for
bruker er hardkodet i kode. `--json`-output inneholder aldri oversatte
tekster, bare koder og data; front-endene oversetter selv. CLI-en velger
språk fra `--lang`, ellers `LANG_UI` i `sandbox.config`, ellers systemets
locale.

### 3.6 `skills/` — ferdigheter sandkassen gir agenten

En **skill** er instruksjoner agenten henter når den trenger dem, i stedet
for å ha dem i `AGENTS.md` hele tiden: én mappe per skill med en `SKILL.md`
som starter med en front matter-blokk (`name`, `description`). Agenten leser
beskrivelsen først og åpner resten bare når den gjelder. Formatet er
agentenes eget; Lyngvi skriver aldri om filene, det eneste Lyngvi bestemmer
er *hvilke* av dem en agent får se.

```
<sandkasse>/skills/
├── skills.json                  # {"schemaVersion":1,"skills":[{"name":…,"enabled":…}]}
├── deliver-change/SKILL.md
└── blocked-host/
    ├── SKILL.md
    └── references/…             # støttefiler følger skillen
```

Regler:

- **Mappene bestemmer hva som finnes.** En skill som er kopiert inn for hånd
  dukker opp uten noe annet, og er på til den slås av. `skills.json` bærer
  bare av/på-flagget; oppføringer uten mappe faller bort neste gang filen
  skrives. Mappenavnet er skillens navn: små bokstaver med bindestrek, høyst
  64 tegn, og det samme som `name` i front matter (ellers laster ikke agenten
  den — den listes med en forklaring i stedet).
- **Sandkassen er hele rekkevidden.** Skills i én sandkassemappe er filer i
  den mappen og ingen andre steder; en annen sandkasse har sine egne. Det er
  hele isolasjonsmekanismen — ingen delt katalog, ingen global installasjon.
- **`skills/` er brukerens.** Ingenting her genereres eller skrives over av
  `upgrade`. `init` legger ned de skillene Lyngvi følger med
  (`templates/skills/`) én gang; en redigert eller slettet startskill kommer
  aldri tilbake.
- **Veien inn i containeren er agentens konfigurasjonskatalog.** For hver
  aktivert agent med `skillsDir` i `agent.json` (kap. 6.1) speiles de påslåtte
  skillene til `state/agents/<id>/<skillsDir>/`, som er montert `rw` i alle
  samtalecontainerne til sandkassen. Lyngvi eier bare navnene den selv har
  lagt der — de står i `.lyngvi-skills` ved siden av — så en skill brukeren
  har installert på agentens egen måte blir liggende.
- **Når en endring virker:** speilingen skjer ved `session ask`, før agenten
  startes, og i `rebuild-config`. Agenten startes på nytt for hver melding, så
  en endret skill gjelder fra *neste melding* i alle samtaler, også de som
  kjører — ikke bare i neste samtale. En skill som er slått av fjernes fra
  agentens katalog, men beholder filene sine i sandkassen. En skill med en
  feil i front matter kopieres aldri inn.
- **En agent uten `skillsDir` får ingen skills.** Da er sandkassens skills
  ikke en del av hvordan den agenten arbeider, og front-endene sier det:
  `skill list` og GUI-siden navngir agentene som faktisk tar imot dem.

Kommandoer: `skill list|show|new|remove|enable|disable|sync` (kap. 4.3).
GUI-et har listen, av/på og editoren (kap. 9.3). Et nettbasert
skillsbibliotek på GitHub er fremtidig arbeid (kap. 11): `skills.json` har
skjemaversjon og én oppføring per skill nettopp for å kunne bære hvor en
skill kom fra senere.

---

## 4. Kjerne, CLI og kommandokontrakt

### 4.1 Oppbygning

```
setup.sh                         # POSIX sh, < 50 linjer: finn kjøretid, exec (kap. 4.6)
lyngvi-core/               # Java 25-modul, ingen JavaFX
  no.lyngvi.core
    config/      Profile, SandboxConfig, DotEnv, Paths, schema-validering
    i18n/        Messages (ResourceBundle)
    runtime/     Podman, Compose, Platform (mac/linux), ProcessRunner
    render/      Containerfile, compose, AGENTS.md, allowlister, cert
    stack/       up/down/status/logs
    session/     Session, Workspace (kopi/klon), Container, Locks, ConversationStore
    ask/         AgentRun, EventStream, AgentAdapter (grensesnitt), ProcessAdapter
    agents/      ClaudeCodeAdapter, OpenCodeAdapter, MockAdapter
    delivery/    PatchBuilder, PatchClassifier, HostGit, Delivery
    gateway/     Manifest, Instances, Secrets, Allowlists, Plugins
    doctor/      Checks
    version/     Version, Changelog, Upgrade, Migrate
    api/         Fasader front-endene bruker: SessionService, StackService, …
    contract/    JSON-records for all output (samme skjema som contract/*.json)
lyngvi-cli/                # main(): argv → api → JSON/JSONL på stdout
lyngvi-gui/                # JavaFX, avhenger av core
lyngvi-gateway-api/        # ren JDK: plugin-grensesnitt + manifestrecords; deles av core og gateway
lyngvi-gateway/            # gatewayens kjøretid; kjører i containeren (kap. 8)
lyngvi-gateway-plugins/    # referanseplugins (confluence, jira) som egne jar-er
agents/<id>/                     # agent.json, image.Containerfile, ev. adapter.sh (kap. 6)
```

Kjernen har én tredjepartsavhengighet: et JSON-bibliotek (Jackson).
Alt annet er JDK. Ingen pakke i kjernen kjenner til JavaFX eller til
CLI-ens argumentparsing.

Vertsavhengigheter (kontrolleres av `doctor`): `podman ≥ 5`,
`podman compose` eller `podman-compose`, `git ≥ 2.30`, `rsync`, `openssl`.
Java følger med produktet (kap. 4.6). `setup.sh` krever bare `/bin/sh`.

### 4.2 Globale flagg og konvensjoner

| Flagg | Betydning |
|---|---|
| `--json` | Maskinlesbar output på stdout. Menneskelig tekst kun på stderr. |
| `--quiet` | Ingen fremdriftsmeldinger på stderr. |
| `--lang <kode>` | Overstyr `LANG_UI` for denne kjøringen. |
| `--no-color` | Ingen ANSI. Standard når stdout ikke er en terminal. |
| `--yes` | Svar ja på bekreftelser. Interaktive spørsmål er ellers kun tillatt når stdin er en terminal. |

Regler for `--json`:

- Stdout inneholder nøyaktig ett JSON-dokument, unntatt strømmende
  kommandoer (merket *JSONL* under) som skriver ett JSON-objekt per linje.
- Alle dokumenter har `"ok": true|false`. Ved `false` finnes
  `"error": {"code": "<SCREAMING_SNAKE>", "message": "<engelsk>", "data": {…}}`.
- Feilkoder er stabile og dokumentert i `contract/errors.json`. GUI-et
  oversetter på kode.
- Tidsstempler er epoch-millisekunder. Stier er absolutte.
- Skjemaer i `contract/<kommando>.schema.json`, generert fra kjernens
  `contract`-records ved bygg. CI validerer output fra ende-til-ende-testene
  mot skjemaene.

Exit-koder:

| Kode | Betydning |
|---|---|
| 0 | OK |
| 1 | Generell feil (`die`) |
| 2 | Ugyldige argumenter |
| 3 | Sandkassen er ikke initialisert / trenger migrering |
| 4 | Validering avvist (f.eks. beskyttet gren, ugyldig navn) |
| 5 | Ekstern kommando feilet (podman, git); detaljer i `error.data.stderr` |
| 6 | Avbrutt (SIGINT/SIGTERM) |
| 7 | Ingen endringer (kun `session review`/`apply` når resultatet er tomt) |

### 4.3 Kommandoliste

Kommandoer merket **G** har en tilsvarende operasjon i kjernens `api`-pakke
som GUI-et kaller direkte. Alle støtter `--json`.

**Oppsett og drift**

| Kommando | Beskrivelse |
|---|---|
| `init [--profile <sti\|url>] [--repo <sti> …] [--agent <id>]` **G** | Førstegangsoppsett. Uten `--profile` brukes `profiles/default.json`. Interaktiv når stdin er TTY og argumenter mangler. |
| `rebuild-config` **G** | Regenerer alle genererte filer fra profil + `sandbox.config`. Idempotent. |
| `doctor` **G** | Preflight. JSON: liste av `{id, level: ok\|warn\|fail, message, hint}`. Exit 1 ved fail. |
| `status` **G** | Sandkasse-, stakk- og samtaleoversikt. |
| `build [--no-cache]` **G** | Bygger basisimage + agentimages. JSONL: byggelogg. |
| `up` / `down` **G** | Starter/stopper felles stakk (proxy, gateway). `down` stopper også alle samtalecontainere. |
| `logs <proxy\|gateway> [--follow] [--tail N]` **G** | JSONL: `{ts, service, line}`. |
| `shell [<samtale-id>]` | Interaktivt skall i en samtalecontainer (ikke JSON). |
| `version [--check]` **G** | Lokal versjon; `--check` sammenligner med profilpakkens/upstreams `VERSION`. |
| `upgrade` **G** | Oppgraderer sandkassens filer fra bundlet mal (GUI) eller git (CLI). Rapporterer om imaget må bygges på nytt. |
| `migrate` **G** | Migrerer en 1.1.2-sandkasse (kap. 12). |

**Konfigurasjon**

| Kommando | Beskrivelse |
|---|---|
| `profile show` / `profile set <sti\|url>` / `profile update` **G** | Vis, bytt, hent ny versjon av profilpakken. Bytte regenererer. |
| `repo list` / `repo add <sti> [--name <n>]` / `repo remove <sti\|navn>` **G** | Som 1.1.2. |
| `domain list` / `domain add <dom> [--note <t>]` / `domain remove <dom>` / `domain reload` **G** | Brukerens tillegg til allowlisten. Hot-reload av Squid. |
| `domain blocked [--follow]` **G** | JSONL: blokkerte forespørsler fra Squid-loggen, aggregert per vert: `{host, count, firstSeen, lastSeen, code}`. Erstatter GUI-ets `BlockedRequestMonitor`. |
| `resources show` / `resources set [--preset <id>] [--mem] [--memswap] [--cpus] [--pids]` **G** | Standardgrenser for nye samtaler. `--preset` setter alle fire; enkeltvalg overstyrer. |
| `resources presets` **G** | Forvalgene i profilen (`resourcePresets`), pluss ett utledet av maskinen, med hvor mange samtaler av den størrelsen maskinen har plass til samtidig. |
| `resources machine` **G** | Podman-maskinens (macOS) eller vertens (Linux) CPU/RAM/disk, og advarsler når grensene ikke henger sammen (1.1.2 kap. 6). |
| `gateway list\|status\|logs\|restart\|rebuild` **G** | Instanser med plugin-type, vertsnavn, secret-status og allowlist-antall. |
| `gateway plugin list` / `gateway plugin add <sti\|url>` / `gateway plugin remove <type>` **G** | Plugin-typer i `gateway/plugins/`. `add` validerer `plugin.json` og `apiVersion`. |
| `gateway instance add --plugin <type> --id <id> --hostname <h> [--alias <a>]… [--config <json>]` / `gateway instance remove <id>` **G** | Skriver manifestet, oppretter allowlist og `secrets/<id>.env.example`, regenererer. |
| `gateway secret set <instans> [--stdin]` / `gateway secret status <instans>` / `gateway secret clear <instans>` **G** | Hemmelighet per instans. GUI bruker alltid `--stdin`. |
| `gateway allow list\|add\|remove <instans> [<oppføring> …]` **G** | Allowlist per instans; normalisering gjøres av instansens plugin (kap. 8.4). |
| `gateway cert status\|renew\|check` **G** | Som 1.1.2. |
| `agent list` **G** | Aktiverte adaptere med `{id, label, authMode, loggedIn, models[]}`. |
| `agent login <id>` **G** | Starter adapterens innloggingsflyt (kap. 6.4). |
| `agent logout <id>` / `agent status <id>` / `agent models <id>` **G** | |
| `skill list` / `skill show <navn>` **G** | Sandkassens skills (kap. 3.6) med av/på-flagg, beskrivelse og eventuell feil, og hvilke aktiverte agenter som tar imot dem. `show` skriver stien og `SKILL.md`. |
| `skill new <navn>` / `skill remove <navn>` **G** | Oppretter en mappe med en `SKILL.md` å fylle ut, eller sletter en skill med mappen sin. |
| `skill enable <navn>` / `skill disable <navn>` **G** | Slår en skill på eller av for sandkassen. Av beholder filene; de holdes bare utenfor agentene. Virker fra neste melding i alle samtaler. |
| `skill sync` **G** | Speiler de påslåtte skillene til `state/agents/<id>/<skillsDir>/` for hver aktivert agent. Gjøres av `ask` og `rebuild-config`; kommandoen finnes for å kunne gjøre det alene. |

**Samtaler** (kapittel 5 gir semantikken)

| Kommando | Beskrivelse |
|---|---|
| `session new [--title <t>] [--repos <n,…> \| --no-repos] [--agent <id>] [--model <m>] [--history <none\|shallow\|full>] [--depth <n>] [--preset <id>] [--mem …]` **G** | Oppretter samtale og arbeidskopi. `--no-repos` gir en samtale uten arbeidskopi (ren spørsmål-og-svar). `--history` overstyrer profil og `sandbox.config` for denne samtalen. JSON: samtalefilen. |
| `session list` **G** | Alle samtaler med containerstatus. |
| `session show <id>` **G** | Samtalefilen. |
| `session rename <id> <tittel>` **G** | |
| `session add-repo <id> <navn> … [--history <none\|shallow\|full>] [--depth <n>]` **G** | Legger arbeidskopier av registrerte repoer til en samtale som allerede finnes (kap. 5.2.1). Avvises mens agenten kjører. JSON: samtalefilen. |
| `session start <id>` / `session stop <id>` **G** | Starter/stopper samtalens container. `ask` starter implisitt. |
| `session status <id>` **G** | Container, aktiv kjøring, siste hendelse, uttakspunkter. |
| `session ask <id> [--model <m>] [--attach <fil> …] [--prompt-file <f>]` **G** | *JSONL.* Sender en melding til agenten. Prompt fra `--prompt-file`, ellers stdin. Strømmer normaliserte hendelser (kap. 5.5). Oppdaterer samtalefilen. SIGTERM avbryter. |
| `session suggest <id> [--from …]` **G** | Ber agenten foreslå grennavn og commit-melding for gjeldende diff. JSON: `{branch, message}`. Tidsavbrudd 60 s, fallback til `git.branchPrefix + <slug av tittel>` og tittel. |
| `session diff <id> [--from start\|last\|<n>] [--repo <r>] [--path <p>]` **G** | Rå unified diff (ikke JSON) eller `--json` med filliste. |
| `session review <id> [--from …]` **G** | Bygger patch, klassifiserer i bøtter, sjekker mot host. JSON (kap. 5.6). |
| `session apply <id> --branch <b> [--message <m>] [--mode commit\|stage] [--repo <r>] [--include <sti> …] [--from …]` **G** | Påfører valgte filer på hostens repo. JSON (kap. 5.7). |
| `session fetch <id> [--path <dir>] [--to <dir>]` **G** | Kopierer en katalog ut av samtalens volum til hosten; som standard `output/`. Symlinker følger ikke med. GUI-ets filpanel gjør det samme per fil. |
| `session export <id> [--to <fil\|katalog>] [--no-git] [--all]` **G** | Skriver hele samtalen — hver arbeidskopi og agentens `output/` — til ett zip-arkiv på hosten (kap. 5.9). Siste utvei når en leveranse ikke lar seg gjennomføre; påfører ingenting. |
| `session gc [--idle <min>]` **G** | Stopper containere som har vært inaktive lenger enn `session.idleStopMinutes`, fjerner containere som har vært inaktive lenger enn `session.idleRemoveHours` (arbeidet ligger i volumet; containeren bygges på nytt ved neste melding), og fjerner containere og volumer uten samtale. GUI kaller den periodisk. |
| `clean [--containers] [--work] [--images] [--cache] [--all] [--older-than <dager>] [--include-undelivered]` **G** | Viser hva sandkassen kan frigjøre, og fjerner det med `--yes`. Uten `--older-than` røres bare objekter uten samtale. Arbeidskopien til en samtale som aldri har levert noe krever `--include-undelivered`; de endringene finnes ikke andre steder. Bare denne sandkassens egne objekter — merkelappen `lyngvi.project` og imagenavn `localhost/lyngvi-*` — aldri noe annet på maskinen. |
| `session remove <id> [--keep-conversation]` **G** | Fjerner container, volum og arbeidskopi. Samtalefilen slettes med mindre flagget er satt. |

### 4.4 Samtidighet og låsing

- Kjernen tar en lås per samtale (`state/locks/<id>.lock`) rundt `new`,
  `ask`, `review`, `apply`, `remove`, og en global lås
  (`state/locks/sandbox.lock`) rundt `rebuild-config`, `up`, `down`,
  `migrate`. Låsen er `FileChannel.lock()` på en låsefil; den frigis av
  operativsystemet når prosessen dør, så forlatte låser finnes ikke. GUI
  og CLI som kjører samtidig mot samme sandkasse låser mot hverandre på
  samme fil.
- Innenfor GUI-prosessen brukes samme lås; to knappetrykk mot samme
  samtale serialiseres.
- `apply` nekter når samtalen har en aktiv `ask` (feilkode `SESSION_BUSY`).
  GUI-et deaktiverer «Hent inn» mens agenten jobber; CLI-en gir feilen.
- Samtalefiler skrives atomisk (temp + `Files.move` med `ATOMIC_MOVE`). De
  er ikke bind-mountet, så flytting er trygt. Filer som *er*
  single-file-mountet (allowlister, `AGENTS.md`) skrives alltid in-place
  (`TRUNCATE_EXISTING`, aldri ny inode).

### 4.5 Genererte filer

`rebuild-config` genererer, i denne rekkefølgen:

1. `proxy/allowed-domains.txt`: seksjon `# profile`, seksjon `# agents`,
   seksjon `# user` (bevares ordrett fra forrige versjon), alle in-place.
2. `proxy/gateway-hosts.txt` fra manifestet.
3. `proxy/squid.conf`: `cache_peer`-linjen oppdateres med `<prefix>.10`.
4. `AGENTS.md` fra `templates/AGENTS.md` + profilpakkens `AGENTS.md.d/*.md`
   + agentens tillegg (`agents/<id>/AGENTS.d/*.md`). In-place.
5. `Containerfile` = `image/base.Containerfile` + fragmenter for
   `toolchains` + `agents/<id>/image.Containerfile` for hver aktivert agent.
   Ett image per agent: `localhost/lyngvi-<agent-id>:<VERSION>`.
6. `docker-compose.yml` for stakken (proxy, gateway) med subnett og UID/GID.
7. `gateway/secrets/<instans>.env.example` og tom `gateway/allowlists/<instans>.txt`
   for hver instans som mangler dem.
8. `gateway/certs/*` hvis manglende eller med feil SAN (alle instansers
   vertsnavn og alias).
9. `state/`-kataloger.

Ingen generert fil skal redigeres av bruker. `doctor` advarer hvis en
generert fil er eldre enn kildene sine (dvs. utdatert).

### 4.6 Distribusjon og kjøretid

En jar kan ikke inneholde det som skal kjøre den. CLI-en leveres derfor som
et **jlink-bilde**: en katalog med nedskåret Java-kjøretid pluss
`lyngvi-core` og `lyngvi-cli` som moduler.

| Egenskap | Verdi |
|---|---|
| Innhold | `java.base`, `java.net.http`, `jdk.crypto.ec`, `java.xml`, kjernens moduler, AppCDS-arkiv |
| Størrelse | 35–60 MB per plattform |
| Plattformer | macOS arm64, macOS x86_64, Linux x86_64, Linux arm64 |
| Base | Temurin 25 (GPL med Classpath Exception; kan videredistribueres) |
| Oppstart | 0,2–0,3 s med AppCDS |
| Plassering | `<sandkasse>/cli/` (gitignorert), eller inne i GUI-appens pakke |

**`setup.sh`** er en POSIX-sh-fil på under femti linjer med nøyaktig denne
logikken, og ingen annen:

1. Finn kjøretid i rekkefølgen: `$LYNGVI_CLI` (sti til et bilde),
   `<sandkasse>/cli/bin/lyngvi`, et bilde inne i en installert
   GUI-app (macOS: `/Applications/lyngvi.app/Contents/runtime`,
   Linux: `/opt/lyngvi/lib/runtime`), til slutt `java` på PATH med
   versjon ≥ 25 (da kjøres `lyngvi-cli.jar` fra `<sandkasse>/cli/`).
2. Finnes ingen: skriv ut plattformens arkivnavn og en `curl`-kommando
   som henter det fra utgivelsessiden, med SHA-256 å verifisere mot, og
   avslutt med exit 3. Med `--download` gjør `setup.sh` det selv og
   verifiserer sjekksummen.
3. `exec` kjøretiden med alle argumenter uendret.

**GUI-appen** bygges med jpackage. Kjøretiden i pakken lages med jlink og
inneholder også CLI-modulene, slik at `init` fra GUI-et kan legge en
symlink `<sandkasse>/cli` til pakkens kjøretid. En bruker som starter med
GUI-et får CLI-en uten nedlasting; en bruker som starter med CLI-en laster
ned ett arkiv.

**Oppgradering.** `VERSION` gjelder både maler og `cli/`. `upgrade` bytter
ut `cli/` (eller symlinken) sammen med malene, og `doctor` feiler hvis
`cli/`-bildets versjon avviker fra `VERSION`.

**Ikke valgt:** kompakte kildefiler (`java setup/Main.java`) rekompilerer
ved hver kjøring og krever et JDK med `jdk.compiler`; de brukes bare i
utviklingsmodus og for engangsskript under `scripts/`. GraalVM
native-image ville gitt oppstart på under 50 ms og én kjørbar fil, men
koster byggkompleksitet (refleksjon, ressurser); det er fremtidig arbeid
(kap. 11) og krever ingen endring i kjernen.

---

## 5. Samtalekontrakt

### 5.1 Katalogstruktur

Samtalens filer — arbeidskopiene og det agenten skriver — ligger i et
**Podman-volum**, ikke på hosten. Volumet heter `<container>-work`, er merket
med `lyngvi.project` og `lyngvi.session`, og er hele `/work` i containeren.
På hosten ligger bare det hosten selv skriver:

```
.sessions/<id>/
  container.env         # generert miljø for containeren (--env-file)
  attachments/          # vedlegg og promptfiler sendt inn med ask (ro i containeren)
  patches/<repo>.patch  # siste review
state/conversations/<id>.json
state/agents/<agent-id>/   # agentens hjemmekatalog, delt mellom samtaler

<container>-work           # Podman-volum:
  <repo>/                  #   arbeidskopi med egen .git (baseline-commit)
  output/                  #   agentens filer til mennesket
```

**Hvorfor volum og ikke bind-mount.** Arbeidskopien er agentens, `.git` i den
også. Lå den på hosten, ville alt agenten skriver — inkludert `hooks/` og
`config` (`core.fsmonitor`, `core.sshCommand`, credential-helpers, alias) —
ligge i hostens filsystem, og hver `git` *vi* kjørte i kopien under `review`
og `apply` ville kjøre agentens programmer som mennesket, utenfor sandkassen.
Med volumet er det ingen host-sti å skrive til, og hosten når filene bare
gjennom kortlevde hjelpecontainere: `--network none`, `--cap-drop ALL`,
`--security-opt no-new-privileges`, basisimaget, samme `--userns keep-id` som
samtalecontaineren, og `git` alltid med `-c core.hooksPath=/dev/null -c
core.fsmonitor=false` på kommandolinjen, der kopiens egen konfigurasjon ikke
når dem. Prisen er at hvert git-kall i kopien er en container (noen hundre
millisekunder), og at mennesket henter ut `output/` med `session fetch` eller
filpanelet i GUI-et i stedet for å åpne en katalog på hosten.

En samtale laget før volumene fantes har arbeidskopien og `output/` på hosten.
Første gang en slik samtale brukes, flyttes begge inn i volumet og fjernes fra
hosten; `session gc` fjerner volumer uten samtale.

Inne i containeren:

```
/work                  ← rw, volumet <container>-work
├── AGENTS.md          ← ro, generert (og CLAUDE.md → AGENTS.md for Claude Code)
├── output/            ← i volumet
├── _attachments/      ← ro, .sessions/<id>/attachments (den eneste host-katalogen
│                        containeren ser, og den skriver bare mennesket i)
├── <repo>/            ← i volumet
└── <repo2>/
/home/agent/           ← agentens hjem; <agent>-spesifikk konfigkatalog er
                          bind-mountet fra state/agents/<agent-id>/
```

Fordi hver samtale har egen container, finnes ikke `/work/sessions/` lenger.
Agenten kan ikke se andre samtaler. Regel 1.1.2 Del B §5.3 punkt 1–2 og
F.5 punkt 7 utgår.

### 5.2 `session new`

Rekkefølgen er sikkerhetskritisk. Prinsippet fra 1.1.2 kap. 4.2 står:
agenten får aldri hostens `.git`. Det farlige i hostens `.git` er ikke
historikken, men `hooks/`, `config` (`core.fsmonitor`, `core.sshCommand`,
credential-helpers, alias, `includeIf`), remotes med innebygd legitimasjon
og `info/`. Objekter og refs er data. Arbeidskopien får derfor alltid et
**nytt** `.git`; hvor mye historikk det inneholder styres av
`copy.history` (kap. 3.1), `sandbox.config` (kap. 3.2) og `--history`.

Kopien bygges i en **midlertidig stagingkatalog** på hosten (`state/tmp/`, inne i
sandkassekatalogen fordi det er et sted Podman kan montere på alle plattformer)
og flyttes inn i samtalens volum med en hjelpecontainer når den er ferdig;
stagingkatalogen slettes umiddelbart etter. Det er trygt fordi den bare
inneholder det hostrepoet allerede har, og fordi ingen container har sett den
mens den fantes: alt som senere skrives i kopien, skrives i volumet. Stegene
under kjører altså i stagingkatalogen, og `session new` krever derfor at
basisimaget er bygget.

For hvert repo:

1. **Les hostens tilstand.** Valider at repoet er en git-arbeidskatalog og
   les `hostSha = git rev-parse HEAD`, `hostBranch = git rev-parse
   --abbrev-ref HEAD` (kan være `HEAD` ved detached), og `hostDirty` fra
   `git status --porcelain`. Dette må skje før kopieringen.
2. **Lag kopien**, avhengig av historikkmodus:

   *`none`* (oppførselen fra 1.1.2):
   ```
   rsync -a --exclude .git --exclude-from=<excludes> <host>/ work/<repo>/
   git -C work/<repo> init -b work
   ```

   *`shallow`* og *`full`*:
   ```
   git clone --no-local --no-hardlinks --template=<tom katalog> \
       --single-branch --branch <hostBranch> --no-tags \
       [--depth <depth>]                       # kun shallow
       file://<host> work/<repo>
   git -C work/<repo> remote remove origin
   rsync -a --exclude .git --exclude-from=<excludes> <host>/ work/<repo>/
   ```
   Ved detached HEAD på host brukes `--revision <hostSha>` i stedet for
   `--branch` (git ≥ 2.49), ellers faller kommandoen tilbake til `none`
   med en advarsel. Ved `full` settes grenen om til `work` etter klonen
   (`git switch -c work`), slik at grennavnet i kopien er det samme i alle
   moduser.

   Hvorfor klonen er trygg:
   - `git clone` kopierer aldri `hooks/`, `config` eller `info/` fra
     kilden. `--template` med en tom katalog hindrer at hostens
     `init.templateDir` legger inn hooks i den nye klonen.
   - `--no-local --no-hardlinks` tvinger en ekte objektkopi; kopien deler
     ingen filer med hostens `.git`.
   - `--single-branch --no-tags` gir agenten bare grenen den skal jobbe
     på, ikke alle lokale grener og tagger.
   - `remote remove origin` fjerner den eneste koblingen tilbake.
   - `.gitmodules` følger med i treet, men ingen submodul-konfig, så
     `git submodule` er passiv. LFS-pekere forblir pekere fordi filteret
     ikke er konfigurert i klonen. Begge deler er ønsket.

   Rsync-steget etterpå legger hostens ucommitede endringer oppå den
   klonede arbeidskatalogen. Ekskluderingene er profilens `copy.excludes`
   pluss repoets egen `.lyngviignore` (samme syntaks som `.gitignore`,
   valgfri). Filer over `copy.maxFileMiB` hoppes over og rapporteres.
3. **Konfigurer kopien:** `user.name`/`user.email` fra profilen,
   `commit.gpgsign=false`, `core.hooksPath=/dev/null`.
4. **Baseline-commit.** `git add -A` og, *bare hvis indeksen avviker fra
   HEAD*, `git commit -m "lyngvi: baseline (uncommitted host changes)"`.
   Les `baseCommit = git rev-parse HEAD`. I `shallow`/`full` med ren host
   er dermed `baseCommit == hostSha`; i `none` er de alltid ulike.
5. **Flytt kopien inn i volumet** (`cp -a` i en hjelpecontainer) og slett
   stagingkatalogen. Fra nå av finnes kopien bare i volumet.
6. **Skriv samtalefilen** (5.4) med
   `base[repo] = {hostSha, hostBranch, baseCommit, hostDirty, history, depth}`.
7. Container startes ikke før første `ask` eller eksplisitt `start`.

Kopien har `.git`, men det er *sandkassens eget*. Agenten kan bruke git
fritt i kopien, inkludert `log`, `blame` og `bisect` når historikk er med;
det er ønsket — og den kan endre `.git/config` og legge inn `hooks/` som den
vil, for det treffer bare den selv: alle våre egne git-kall i kopien kjører i
en hjelpecontainer med `-c core.hooksPath=/dev/null -c core.fsmonitor=false`
(5.1).

**Kostnad og alternativer.** `shallow` med `depth` 100 er valgt som standard
fordi det dekker det agenter faktisk bruker historikk til (nylig kontekst,
`blame` på aktuelle linjer) til en brøkdel av kostnaden ved `full`. For
repoer på flere GB der selv en grunn klon er for dyr, er `none` riktig.
Et delt objektlager (`objects/info/alternates` mot et read-only mount av
hostens `.git/objects`) er bevisst *ikke* spesifisert: det ville gitt en
mount av en del av hostens `.git` og gjøre kopien sårbar for `git gc` på
host. Det kan vurderes som fremtidig arbeid for monorepoer (kap. 11).

### 5.2.1 `session add-repo` — repo til en samtale som er i gang

Behovet for et repo til oppstår midt i en samtale: agenten finner ut at
endringen også må gjøres i klienten, eller i biblioteket under. Da skal
man ikke måtte starte samtalen på nytt.

Hele `/work` er samtalens volum (5.1). En arbeidskopi som legges i volumet
nå, dukker derfor opp i containeren samtalen allerede kjører i, under
`/work/<navn>`, uten at noe bygges eller startes på nytt — og agenten
beholder økten sin. Kopien lages nøyaktig som i 5.2, steg for steg, med
samme staging på host og samme `--history`/`--depth`-valg.

Reglene:

1. **Bare registrerte repoer.** Navnet må finnes i `sandbox.config`
   (`repo add` først). Et navn samtalen allerede har, avvises.
2. **Avvises mens agenten kjører** (`state/runs/<id>.json` finnes). Et tre
   som dukker opp under agenten midt i en tur er ingen tjeneste, og
   kjøringen eier samtalefilen.
3. **Avvises hvis `/work/<navn>` finnes i volumet fra før** — en katalog
   agenten selv har laget skal ikke blandes med hostens tre.
4. **Eget utgangspunkt.** `base[repo]` skrives for den nye kopien alene;
   de som alt var der, beholder sitt. Et uttak måler derfor hver kopi fra
   der *den* startet (5.8), ikke fra der samtalen startet.
5. Agenten får ikke beskjed av seg selv. Kopien er der, men samtalen er
   menneskets: si i neste melding hva den skal brukes til.

GUI-et har det i kontekstmenyen til en samtale («Koble til repo…», 9.4).

### 5.3 Container per samtale

`session start` kjører (forenklet):

```
podman run -d --name lyngvi-<project>-<kort-id> \
  --label lyngvi.session=<id> --label lyngvi.project=<project> \
  --network <project>_internal \
  --add-host <hostname>:<prefix>.10 …            # fra manifest
  --userns keep-id:uid=<UID>,gid=<GID> \
  --cap-drop ALL --security-opt no-new-privileges --security-opt label=disable \
  --memory <mem> --memory-swap <memswap> --cpus <cpus> --pids-limit <pids> \
  --env-file <generert env for samtalen> \
  -v <container>-work:/work:rw \                 # samtalens volum: hele /work
  -v <sandkasse>/AGENTS.md:/work/AGENTS.md:ro,z \
  -v <sandkasse>/.sessions/<id>/attachments:/work/_attachments:ro,z \
  -v <sandkasse>/state/agents/<agent>:/home/agent/<konfigkatalog>:rw,z \
  -v <sandkasse>/gateway/certs/gateway.crt:/etc/lyngvi/gateway.crt:ro,z \
  localhost/lyngvi-<agent>:<VERSION> sleep infinity
```

- Miljøet inneholder `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` (store og små),
  `JAVA_TOOL_OPTIONS` med proxy og DNS-innstillinger (1.1.2 Del B §2.4),
  `GATEWAY_URL` og `<INSTANS>_GATEWAY_URL` per gateway-instans,
  `LYNGVI_SESSION=<id>`,
  pluss adapterens egne variabler (kap. 6.2).
- `sleep infinity` som PID 1 (via `tini` i imaget); alle agentkjøringer er
  `podman exec`. Det gjør avbrudd, gjenbruk og `shell` enkelt.
- Containeren er ephemeral for alt utenfor mountene. **Verktøycacher deles**
  mellom sandkassens samtaler etter `cache` i profilen (kap. 3.1): ett navngitt
  volum per cachekatalog, `lyngvi-<prosjekt>-cache-<id>`, og bare for de
  verktøykjedene imaget faktisk har. Id-ene er et lukket sett i koden
  (`CacheVolumes`), så en profil kan aldri montere en katalog den finner på selv.
  `mode` er `off`, `shared` (skrivbart volum) eller `readonly` (samme volum bak
  et overlay: samtalen leser alt, og det den selv laster ned forsvinner med
  containeren). `session new --cache` overstyrer per samtale — `off` er det
  riktige for kode man ikke har grunn til å stole på, siden en delt skrivbar
  cache er den ene tingen samtalene i en sandkasse har felles. Mounts fryses når
  containeren lages, så en endret innstilling slår inn ved neste opprettelse;
  containeren bærer `LYNGVI_CACHE` i miljøet nettopp for at `session start` skal
  se forskjellen og bygge den på nytt.
  Standardprofilen deler `npm`, `pip` og Go-cachene — de som verifiserer det de
  leser og tåler flere skrivere. `m2`, `gradle` og `gradle-dists` finnes i
  tabellen, men er ikke med som standard: Maven tar ingen lås mellom prosesser og
  sjekker ikke en jar som allerede ligger i det lokale repoet.
- `session stop` gjør `podman stop` og beholder containeren (rask restart).
  `session remove` gjør `podman rm -f` og `podman volume rm -f <container>-work`:
  arbeidskopien forsvinner med samtalen, som før.
- `session gc` stopper containere uten `exec` de siste
  `session.idleStopMinutes`; sist aktiv leses fra samtalefilen. Den fjerner
  også containere og volumer som ikke lenger har en samtale.
- `session fetch <id> [--path <dir>] [--to <dir>]` kopierer en katalog ut av
  volumet (som standard `output/`) til hosten. Symlinker følger ikke med:
  det som forlater sandkassen er filer.

### 5.4 Samtalefilen `state/conversations/<id>.json`

```json
{
  "schemaVersion": 2,
  "id": "6f1c…",
  "title": "Fix login redirect",
  "createdAt": 1757000000000,
  "updatedAt": 1757000600000,
  "lastActiveAt": 1757000600000,
  "agent": "claude-code",
  "agentSessionId": "9a2b…",
  "model": "claude-sonnet-5",
  "repos": ["api", "web"],
  "resources": { "mem": "8g", "memswap": "12g", "cpus": "4.0", "pids": "2048" },
  "container": { "name": "lyngvi-acme-6f1c", "state": "running" },
  "base": {
    "api": { "hostSha": "abc…", "hostBranch": "main", "baseCommit": "abc…", "hostDirty": false, "history": "shallow", "depth": 100 },
    "web": { "hostSha": "123…", "hostBranch": "feature/x", "baseCommit": "456…", "hostDirty": true, "history": "none", "depth": null }
  },
  "deliveries": [
    {
      "at": 1757000500000,
      "branch": "agent/fix-login-redirect",
      "message": "Fix login redirect on mobile",
      "mode": "commit",
      "repos": {
        "api": { "files": 3, "copyCommit": "789…", "hostCommit": "0ab…" }
      }
    }
  ],
  "messages": [
    { "id": "m1", "role": "user", "at": 1757000010000, "text": "…", "attachments": [] },
    { "id": "m2", "role": "assistant", "at": 1757000050000, "text": "…",
      "work": [ { "type": "tool", "name": "Bash", "input": "…", "output": "…", "isError": false } ],
      "usage": { "input": 1200, "output": 800, "cacheRead": 0, "cacheWrite": 0, "costUsd": null },
      "stopReason": "end_turn" }
  ]
}
```

- `session ask` skriver både brukerens melding og agentens svar. CLI og GUI
  har derfor samme historikk (endring fra 1.1.2 kap. 4.5, der CLI-samtaler
  var uten meldinger).
- `deliveries[].repos[r].copyCommit` er commiten i arbeidskopien som
  representerer uttaket. `--from last` betyr «fra nyeste `copyCommit` for
  repoet, ellers `baseCommit`». `--from <n>` er indeks i `deliveries`.
  `--from start` er `baseCommit`.
- GUI-spesifikke felt (ulest-markering, sist valgt fane) lagres *ikke* her,
  men i GUI-ets egne innstillinger, nøklet på samtale-id.

### 5.5 Hendelsesstrøm fra `session ask` (JSONL)

Adapteren produserer strømmen; kjernen validerer, beriker med `ts` og
`seq`, oppdaterer samtalefilen og leverer hendelsene videre som typede
objekter (`api.AgentEvent`). CLI-en serialiserer dem til JSONL på stdout;
GUI-et abonnerer på dem i prosessen. Formatet under er JSONL-formen, som
også er kontrakten mot eksterne adaptere.

| `type` | Felt | Betydning |
|---|---|---|
| `start` | `agent`, `agentSessionId`, `model` | Kjøringen har startet. Sendes alltid først. |
| `text` | `delta` | Del av svartekst (markdown). |
| `reasoning` | `delta` | Del av resonnering, hvis agenten eksponerer det. |
| `tool_start` | `id`, `name`, `input` (string) | Verktøykall begynner. |
| `tool_end` | `id`, `output` (string, kan være trunkert), `isError` | Verktøykall ferdig. |
| `notice` | `level` (`info\|warn\|error`), `text` | Melding fra adapter eller agent (f.eks. «kontekst komprimert»). |
| `usage` | `input`, `output`, `cacheRead`, `cacheWrite`, `costUsd` (nullable), `contextUsed`, `contextMax` | Kan sendes flere ganger; siste gjelder. |
| `done` | `stopReason` (`end_turn\|cancelled\|error\|max_turns`) | Alltid siste hendelse. |
| `error` | `code`, `message` | Etterfølges alltid av `done` med `stopReason: error`. |

Kjernen legger til `"ts"` og `"seq"` på hver hendelse. Ukjente
hendelsestyper fra adapteren logges og droppes. Tekst i `delta`
er data, ikke instruksjoner, og GUI-et rendrer den som markdown uten
skript.

### 5.6 `session review`

1. **Bygg patch per repo i arbeidskopien**, uten å røre indeksen agenten
   eventuelt bruker. Hele sekvensen kjører i én hjelpecontainer i volumet
   (5.1), med arbeidskatalog `/work/<repo>` og `git` som
   `git -c core.hooksPath=/dev/null -c core.fsmonitor=false`:
   ```
   export GIT_INDEX_FILE=/tmp/lyngvi-index
   git read-tree HEAD && git add -A
   tree=$(git write-tree); unset GIT_INDEX_FILE
   git diff --binary --find-renames <fra-commit> $tree    # stdout → patches/<repo>.patch på hosten
   ```
   Dette fanger også ucommitede og uindekserte filer, og arbeid agenten
   selv har committet (1.1.2 F.5 punkt 2). Patchen er det eneste som
   kommer ut av volumet, og den er data: hosten klassifiserer den uten å
   kjøre noe fra kopien.
2. **Klassifiser på hosten** med `git apply --numstat` og
   `git apply --summary` på patchen, pluss parsing av `diff --git`-linjer:

   | Bøtte | Kriterium |
   |---|---|
   | `reject` | Sti med `..`-segment, absolutt sti, sti med `.git/`-segment, sti som ikke er en gyldig UTF-8-streng, eller en patch `git apply --check` ikke kan parse. |
   | `hold` | Ny symlink (`create mode 120000`), endret symlink, ny fil med kjørbar modus (`100755`), modusendring til `100755`, binærfil (numstat `-\t-`), fil over `copy.maxFileMiB`. |
   | `apply` | Alt annet. |

   Avviste filer *fjernes ikke* fra patchen i det stille; de rapporteres
   med `reason` og utelates ved `apply` uansett hva brukeren velger.
3. **Sjekk mot hostens repo**: `git apply --check --include=<sti>` per fil i
   `apply`/`hold` mot hostens arbeidskatalog gir `conflict: true|false`.
   Les hostens gren, om den er beskyttet, og om arbeidskatalogen er skitten.
4. JSON:
   ```json
   {
     "ok": true,
     "from": { "kind": "last", "describe": "since delivery 2025-09-04 10:12 (agent/fix-login)" },
     "repos": {
       "api": {
         "patch": "/…/.sessions/<id>/patches/api.patch",
         "fromCommit": "789…",
         "host": { "path": "/Users/alice/src/api", "branch": "main", "protected": true, "dirty": false },
         "files": [
           { "path": "src/auth.ts", "change": "modified", "bucket": "apply", "additions": 12, "deletions": 3, "binary": false, "conflict": false },
           { "path": "bin/run.sh", "change": "added", "bucket": "hold", "reason": "EXECUTABLE", "mode": "100755", "additions": 20, "deletions": 0, "binary": false, "conflict": false },
           { "path": "../secrets", "change": "added", "bucket": "reject", "reason": "PATH_TRAVERSAL", "additions": 1, "deletions": 0 }
         ]
       }
     }
   }
   ```
   Tom `files` i alle repoer gir exit 7 og `error.code = NO_CHANGES` med
   `data.alternatives` (andre `--from`-valg som *har* endringer), slik at
   GUI-et kan tilby å bytte utgangspunkt (1.1.2 kap. 4.4 punkt 1).

### 5.7 `session apply`

Argumenter: `--branch` (påkrevd), `--message` (påkrevd ved `--mode commit`),
`--mode commit|stage` (standard `commit`), `--repo` (gjenta; standard alle
repoer med endringer), `--include <sti>` (gjenta; standard alle filer i
`apply`-bøtta; filer i `hold` må nevnes eksplisitt; filer i `reject` gir
feil `REJECTED_FILE`), `--from` (standard som `review`).

Per repo, på hosten:

1. Valider grennavn (`git check-ref-format --branch`) og at den ikke er i
   `git.protectedBranches` eller repoets default-gren (`origin/HEAD` hvis
   den finnes). Brudd gir exit 4, `PROTECTED_BRANCH`.
2. Kjør `review` på nytt internt og bruk den ferske patchen (aldri en
   gammel fil).
3. Ta vare på hostens skitne tilstand uten å røre den: `git stash create`
   gir en commit som beskriver arbeidskatalog og indeks, og lar begge stå
   som de er. Patchen måles fra arbeidskopiens utgangspunkt, og hosten har
   ofte det utgangspunktet bare i arbeidskatalogen — forrige leveranse gikk
   ut med `--mode stage`, eller brukeren har egne endringer i filene. Å
   stashe det bort ville tatt patchens pre-image med seg og gjort alt etter
   første leveranse umulig uten en commit på hosten først.
4. Bytt gren når den ikke allerede er den aktive: `git switch <branch>` hvis
   den finnes, ellers `git switch -c <branch>` fra nåværende HEAD. Git tar
   lokale endringer med seg over. Nekter git fordi de ville blitt
   overskrevet — og bare da — `git stash push -u -m lyngvi` først, og
   registrer at stash ble gjort.
5. `git apply --index --include=<sti>…` for de valgte filene. Feiler det,
   prøves `git apply --index --3way` én gang: hosten har da pre-image-blobene
   patchen refererer til (de importeres fra arbeidskopiens utgangspunkt, så
   det virker også med `history: none`), og konflikter blir tre-veis med
   konfliktmarkører i stedet for avvisning. Resultatet rapporteres som
   `conflict: true` per fil. Avviser også den filene helt — som `--index`
   gjør når indeks og arbeidskatalog er ulike for en fil brukeren har endret
   uten å stage — ryddes forsøket bort og patchen påføres arbeidskatalogen
   (`git apply` uten `--index`), hvoretter de leverte stiene stages; det gis
   et `notice` om at brukerens egne uindekserte endringer i de filene stages
   sammen med leveransen. Feiler også det, rulles det tilbake
   (`git reset --hard`, filene patchen opprettet fjernes, `git switch -`,
   snapshotet fra punkt 3 legges tilbake, eventuell stash pop) og
   `APPLY_FAILED` rapporteres med git-output.
6. `--mode commit`: `git commit -m <message> -- <valgte stier>` (aldri `-a`,
   og aldri resten av indeksen: det brukeren hadde staget selv blir stående
   staget). `--mode stage`: la stå staget.
7. `git stash pop` hvis stash ble gjort. Konflikt her rapporteres som
   `notice` med `level: warn`, ikke som feil; endringene er påført. Git nekter
   å poppe inn i et tre med umergede filer; da blir stash-oppføringen liggende
   og brukeren får beskjed om den. En oppføring droppes bare når innholdet
   faktisk landet i treet.
8. I arbeidskopien: `git add -A -- <valgte stier> && git commit -m "lyngvi: delivered <branch>" -- <valgte stier>`
   → `copyCommit`. Filer som ikke ble valgt forblir uncommitet i kopien og
   dukker opp ved neste `review` — også det agenten måtte ha staget selv.
   Stiene filtreres per kommando før de brukes: `add` får dem som finnes i
   indeksen eller på disk, `commit` i tillegg dem som bare finnes i `HEAD`.
   Ved en omdøping er det gamle navnet bare i `HEAD` når agenten har staget
   den selv, og ingen av stedene når agenten har commitet den, og én pathspec
   som ikke treffer noe får git til å avvise hele kommandoen. Samme regel som
   på vertssiden (`HostGit.matchable`).
9. Oppdater samtalefilen med uttakspunktet.

JSON: `{ok, deliveryIndex, repos: {api: {branch, hostCommit, copyCommit, files: 3, stashed: true, stashPopConflict: false}}}`.

### 5.8 Utgangspunkt («Utgangspunkt for diffen»)

Alternativene og standardvalget er som 1.1.2 kap. 4.4 punkt 1. `session review`
uten `--from` bruker `last` når `deliveries` er ikke-tom, ellers `start`.
GUI-et fyller nedtrekkslisten fra `session show`.

### 5.9 `session export`

Argumenter: `--to <fil|katalog>` (standard `./lyngvi-<tittel>-<tid>.zip`; en
katalog gir arkivet et navn inni den), `--no-git`, `--all`.

Hele samtalen som ett zip-arkiv på hosten: én katalog per arbeidskopi, pluss
agentens `output/`. Dette er veien ut som ikke avhenger av noe annet — ikke av
hostens repo, ikke av at en patch går inn, ikke av git i det hele tatt — og
finnes fordi en leveranse kan vise seg umulig mens arbeidet fortsatt må være
tilgjengelig.

1. Arkivet bygges **inne i** volumet av `zip` fra basisimaget, i en
   hjelpecontainer (5.1), med en midlertidig katalog i sandkassen montert som
   `$OUT`. `find` lister hva som skal med; den følger ikke symlinker, så ingen
   lenke forlater sandkassen (samme regel som `session fetch`), og en lenke til
   sin egen katalog (`ln -s . x`) kan ikke få `zip` til å kopiere undertreet én
   gang per nivå. Kataloger og mønstre er posisjonsargumenter til skriptet,
   aldri del av skriptteksten.
2. **Utelatt**: `.git` ved `--no-git`, og profilens `copy.excludes`
   (byggeresultat og avhengigheter, som heller ikke kom *inn* i sandkassen) med
   mindre `--all` er satt. Når `.git` følger med, utelates alltid `.git/hooks`
   og `.git/config`: arbeidskopiens `.git` er agentens å skrive i, arkivet pakkes
   ut på menneskets maskin, og `EXPORT.txt` ber dem kjøre `git` der — de to
   stedene et repo holder programmer git kjører som den som kjører git
   (`hooks/`, og `core.hooksPath`/`core.fsmonitor`/`core.pager`/
   `core.sshCommand`/aliaser i `config`) skal ikke ut. Det er samme grunn som at
   hosten aldri kjører git i volumet uten å slå dem av (5.1), og ingenting går
   tapt: `log`, `status`, `diff` og `fetch` virker uten dem.
3. Arkivet får `EXPORT.txt` som første oppføring: samtale, agent, tidspunkt,
   hvilke kataloger arkivet har og hva hver arbeidskopi startet fra, hva som ble
   utelatt, og kommandoene som henter arbeidet ut (`git log`,
   `git diff <utgangspunkt>`, `git fetch <katalog> work`).
4. Det ferdige arkivet flyttes fra sandkassens temp-område til `--to`, så en
   halvskrevet fil aldri ligger der brukeren pekte.
5. Ingenting påføres og ingen uttakspunkt flyttes: `review` etter en eksport
   sier det samme som før den. `zip`-koden tolkes: 12 («ingenting å gjøre») gir
   `NO_CHANGES`, 18 (en fil kunne ikke leses) gir `partial: true` og et arkiv
   som ellers står, 127 (ingen `zip` i imaget) gir `VALIDATION_FAILED`.

JSON: `{ok, path, bytes, files, roots: ["api", "output"], excluded: ["node_modules", …], partial: false}`.

GUI-et har knappen «Eksporter ZIP» ved siden av «Git Diff» i endringsstripen
(9.4). Den er aldri deaktivert av hva en gjennomgang fant — at gjennomgangen
ikke finner noe, eller feiler, er en av grunnene til å bruke den.

---

## 6. Agentadapter-kontrakt

### 6.1 Katalog og `agent.json`

```
agents/<id>/
  agent.json
  adapter.sh
  image.Containerfile      # fragment: installerer agenten i basisimaget
  AGENTS.d/*.md            # tillegg til AGENTS.md, valgfritt
```

```json
{
  "schemaVersion": 1,
  "id": "claude-code",
  "label": "Claude Code",
  "version": "2.0.0",
  "configDir": "/home/agent/.claude",
  "skillsDir": "skills",
  "instructionFile": "CLAUDE.md",
  "auth": {
    "modes": ["subscription", "api-key"],
    "default": "subscription"
  },
  "egress": [
    "api.anthropic.com",
    "claude.ai",
    "console.anthropic.com",
    "platform.claude.com",
    "statsig.anthropic.com"
  ],
  "capabilities": {
    "streaming": true,
    "reasoning": true,
    "models": true,
    "usage": true,
    "attachments": ["image/png", "image/jpeg"],
    "resume": true
  },
  "env": {
    "CLAUDE_CONFIG_DIR": "/home/agent/.claude"
  }
}
```

- `configDir` monteres fra `state/agents/<id>/`. Det er her legitimasjon og
  agentens egne innstillinger bor. Katalogen er `0700`.
- `skillsDir` er katalogen *inne i* `configDir` der agenten leser ferdigheter
  (skills) fra. De påslåtte skillene i sandkassen speiles dit før hver melding
  (kap. 3.6). Mangler feltet, tar ikke agenten imot skills, og sandkassens
  skills er ikke en del av hvordan den arbeider.
- `instructionFile` gjør at `render` oppretter en symlink
  `/work/<instructionFile> → /work/AGENTS.md` i containeren ved start
  (adapteren gjør det i `prepare`), hvis agenten ikke leser `AGENTS.md`
  selv.
- `egress` legges inn i allowlisten når adapteren er aktivert. Listen skal
  være den *minste* som gjør agenten funksjonell; telemetridomener er
  valgfrie og markeres `"optional": true` i en objektform
  (`{"host": "sentry.io", "optional": true}`).

### 6.2 Adaptergrensesnittet

En adapter finnes i én av to former, begge bak samme Java-grensesnitt
`AgentAdapter` i kjernen:

- **Innebygd:** en Java-klasse i `core.agents` (referanseadapterne og
  mock-adapteren). Den kjører `podman exec` selv via `ProcessRunner` og
  produserer `AgentEvent`-objekter direkte.
- **Ekstern:** `agents/<id>/adapter.sh` (eller en hvilken som helst
  kjørbar fil), pakket inn av `ProcessAdapter` i kjernen. Kjernen kaller
  `adapter.sh <aksjon>` med miljøvariabler, aldri med posisjonelle
  argumenter som kan inneholde brukerdata, og parser JSONL fra stdout.

En adapter som kjører *utenfor* samtalecontaineren når arbeidskopiene gjennom
`AS_VOLUME`, ikke gjennom en host-sti: det finnes ingen (5.1). Formen er den
samme som kjernens egne hjelpecontainere, for eksempel
`podman run --rm -v "$AS_VOLUME":/work -e WORK=/work --entrypoint sh "$AS_IMAGE" -c '… "$WORK/<repo>" …'`.

Finnes både en innebygd klasse og en `adapter.sh` for samme `id`, vinner
`adapter.sh` (slik kan en organisasjon overstyre en referanseadapter uten
å bygge kjernen). Tabellen under er prosesskontrakten; den innebygde
formen har de samme aksjonene som metoder.

| Aksjon | Inn (env) | Ut |
|---|---|---|
| `prepare` | `AS_CONTAINER`, `AS_SESSION_ID` | Kjøres én gang etter `session start`. Oppretter symlinker, skriver agentkonfig for sandkassen. Exit ≠ 0 stopper containeren. |
| `ask` | `AS_CONTAINER`, `AS_SESSION_ID`, `AS_VOLUME` (samtalens volum), `AS_IMAGE` (image å montere det med), `AS_AGENT_SESSION_ID` (tom første gang), `AS_MODEL`, `AS_PROMPT_FILE` (host-sti), `AS_ATTACHMENTS` (NUL-separert liste host-stier, allerede kopiert til `/work/_attachments/` i containeren), `AS_WORKDIR` (`/work`) | JSONL-hendelser (5.5) på stdout. Diagnostikk på stderr. Må svare på SIGTERM ved å avslutte `podman exec` og sende `done` med `cancelled`. |
| `suggest` | som `ask` + `AS_DIFF_FILE` | JSON `{branch, message}` på stdout. |
| `models` | `AS_CONTAINER` (kan være tom; adapteren kan da starte en midlertidig container) | JSON `[{id, label, default}]`. |
| `usage` | `AS_CONTAINER` | JSON med kontoforbruk hvis agenten tilbyr det, ellers `{ "supported": false }`. Kjente medlemmer: `plan`, `note`, `resetAt` (epoke-millisekunder eller ISO-8601) og `limits`, en liste av `{id, label, used, limit, unit, resetAt}` der `limit` kan mangle (løpende sum uten tak). Alt annet beholdes som det er og vises av front-endene. |
| `auth-status` | — | JSON `{loggedIn, mode, account, expiresAt}`. Leser `state/agents/<id>/`. |
| `login` | `AS_AUTH_MODE`, `AS_JSON` (`1` når `setup.sh` kjøres med `--json`) | Interaktiv når stdin er TTY og `AS_JSON` ikke er satt. Ellers JSONL: `{type:"url", url, code}` for nettleserbasert OAuth, deretter `{type:"done", loggedIn}` når flyten er fullført. Se 6.3 og 6.4. |
| `logout` | — | Fjerner legitimasjon fra `state/agents/<id>/`. |
| `version` | — | JSON `{adapter, agent}` (installert agentversjon i imaget). |

Adapteren har ikke lov til å lese eller skrive samtalefilen; det gjør
kjernen. Adapteren har ikke lov til å kalle `podman run`; containere
eies av kjernens `session`-pakke.

### 6.3 Referanseadapter: Claude Code

> Flaggnavn og hendelsesformat under er slik de er kjent ved skrivetidspunkt
> og **skal verifiseres mot den versjonen som pinnes i `image.Containerfile`**.
> Adapteren pinner én versjon; oppgradering er en bevisst handling.

- **Installasjon:** `npm install -g @anthropic-ai/claude-code@<versjon>` i
  fragmentet, med `npm_config_ignore_scripts=true` slått *av* kun for denne
  ene installasjonen hvis pakken krever det (dokumenteres i fragmentet).
- **`ask`:** `podman exec -i -w /work $AS_CONTAINER claude -p
  --output-format stream-json --verbose --permission-mode bypassPermissions
  [--session-id $uuid | --resume $AS_AGENT_SESSION_ID] [--model $AS_MODEL]`,
  prompt på stdin. `bypassPermissions` er riktig *inne i* sandkassen:
  containeren er selve tillatelsesgrensen. Adapteren mapper:
  - `system` (init) → `start` med `session_id`
  - `assistant` med `text`-blokker → `text`; `thinking`-blokker → `reasoning`
  - `assistant` med `tool_use` → `tool_start`; `user` med `tool_result` → `tool_end`
  - `result` → `usage` (`usage.input_tokens`, `output_tokens`,
    `cache_read_input_tokens`, `cache_creation_input_tokens`, `total_cost_usd`)
    og `done`.
- **Instruksjoner:** Claude Code leser `CLAUDE.md` i cwd og foreldre;
  `prepare` lager `/work/CLAUDE.md → AGENTS.md`.
- **Legitimasjon:** `configDir` = `/home/agent/.claude`. Abonnement via
  OAuth (Pro/Max): `login` starter agentens innloggingsflyt i en midlertidig
  container med `state/agents/claude-code/` montert, fanger URL-en agenten
  skriver, og rapporterer den (GUI åpner nettleser på hosten). Alternativ
  for headless bruk: `claude setup-token` på hosten og lagring av
  resultatet som `CLAUDE_CODE_OAUTH_TOKEN` i `state/agents/claude-code/env`
  (`0600`), som adapteren legger inn i containerens miljø. API-nøkkel
  (`ANTHROPIC_API_KEY`) støttes på samme måte.
- **Egress:** listen i 6.1. Adapteren skal også sette
  `DISABLE_TELEMETRY=1`/tilsvarende der agenten støtter det, slik at
  valgfrie domener ikke trengs.
- **`suggest`:** én `-p`-kjøring uten `--resume`, med diffen i prompten og
  instruks om å svare på formen `BRANCH: …` / `MESSAGE: …`. Parses av
  adapteren.

### 6.4 Referanseadapter: OpenCode

> Samme forbehold om verifisering.

- **Installasjon:** `npm install -g opencode-ai@<versjon>` (eller
  binærnedlasting med sjekksum) i fragmentet.
- **`ask`:** `podman exec -i -w /work $AS_CONTAINER opencode run
  --format json [--session $AS_AGENT_SESSION_ID] [--model $AS_MODEL]`
  med prompt som argument lest fra fil inne i containeren
  (`/work/_attachments/prompt.txt`) for å unngå argumentlengdegrenser.
  Adapteren mapper OpenCodes hendelser (tekst, verktøykall, steg, ferdig)
  til 5.5. Alternativ som anbefales vurdert ved implementasjon: `opencode
  serve` i containeren og adapteren som HTTP/SSE-klient via `podman exec
  curl`; det gir renere strøm og avbrudd, men er mer kode.
- **Instruksjoner:** OpenCode leser `AGENTS.md` direkte. Ingen symlink.
- **Konfig:** `prepare` skriver `/home/agent/.config/opencode/opencode.json`
  fra en mal i adapteren, med leverandøroppsett fra `state/agents/opencode/`.
  Ollama på hosten støttes som i 1.1.2 (`host.containers.internal:11434`,
  Squid-regel som i 1.1.2 Del B §3.2).
- **Legitimasjon:** `opencode auth login` i midlertidig container med
  `state/agents/opencode/` montert som `~/.local/share/opencode/`.
  Anthropic-abonnement (Claude Pro/Max) velges i den flyten. Andre
  leverandører (API-nøkler, Copilot via device flow) fungerer gjennom samme
  mekanisme.
- **Egress:** avhenger av valgt leverandør. `agent.json` lister
  Anthropic-domenene pluss `models.dev` (OpenCode henter modellkatalog
  derfra). Ved bruk av andre leverandører legger brukeren til domener med
  `domain add`; adapterens `auth-status` rapporterer hvilke domener valgt
  leverandør trenger, og `doctor` advarer hvis de mangler.

### 6.5 Valgfri adapter: GitHub Copilot CLI

Porteres fra 1.1.2 (`CopilotService`/`CopilotJsonParser`) til
`agents/copilot-cli/adapter.sh`. Ikke del av v1-leveransen, men
kontrakten er laget slik at den passer: `--session-id`, ANSI-stripping og
footer-filtrering blir adapterens ansvar.

### 6.6 Begrensning som beholdes: legitimasjon i containeren

Agentens OAuth-fil eller token er montert i containeren, og agentens
API-domener er allowlistet. En kompromittert agent kan derfor bruke
legitimasjonen mot leverandørens API (f.eks. starte egne samtaler) og,
dersom `github.com` er allowlistet i profilen, pushe med et eventuelt
GitHub-token. Dette er bevisst akseptert i denne versjonen (B6). Tiltak
som *er* med:

- Legitimasjonen er per agent, ikke per bruker: bruk en egen konto eller
  et eget abonnement for sandkassen der det er mulig.
- Ingen GitHub-token i containeren med mindre profilen eller brukeren
  eksplisitt legger det inn (`agent login` for Copilot-adapteren, eller
  `.env`).
- `doctor` advarer når `github.com` er allowlistet *og* et GitHub-token er
  konfigurert.

Fremtidig lukking er beskrevet i kapittel 11.

---

## 7. Kjøremiljø

### 7.1 Basisimage

`image/base.Containerfile`:

- `FROM docker.io/library/debian:bookworm-slim@sha256:<digest>` (pinnet,
  multi-arch; CI verifiserer som i 1.1.2 Del B §9).
- APT: `git ca-certificates curl tini openssh-client zip unzip ripgrep jq
  nano less procps`. Ingen editorer utover nano.
- Bruker `agent` med UID/GID fra build-args (`<din-uid>`/`<din-gid>`
  substitueres av `render`). Hjem `/home/agent`.
- Entrypoint `scripts/docker-entrypoint.sh` (fra 1.1.2 Del B §4.1,
  generalisert): bygger CA-bundle av system + `/certs/*.pem` +
  `/etc/lyngvi/gateway.crt`, eksporterer `CURL_CA_BUNDLE`,
  `SSL_CERT_FILE`, `REQUESTS_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS`, importerer
  i alle JDK-er som finnes, skriver `~/.npmrc`, `~/.m2/settings.xml` og
  `pip.conf` fra `mirrors` og proxy, og `exec "$@"`.
- Herding som 1.1.2 Del B §1.9: `npm_config_ignore_scripts=true`,
  `YARN_ENABLE_SCRIPTS=false`, `GIT_TERMINAL_PROMPT=0`,
  `GIT_CONFIG_NOSYSTEM=1`, GPG-signering av, committer fra profilen,
  `JAVA_TOOL_OPTIONS` med DNS-timeouts.
- `ENTRYPOINT ["/usr/bin/tini", "--", "/home/agent/docker-entrypoint.sh"]`,
  `CMD ["sleep", "infinity"]`.

### 7.2 Verktøykjede-fragmenter

`image/fragments/<name>.Containerfile` legges til i rekkefølgen i
`profile.toolchains`. Hvert fragment er selvstendig og idempotent.
Medfølgende fragmenter i v1:

| Navn | Innhold |
|---|---|
| `node22` | Node 22 fra NodeSource eller offisiell tarball med sjekksum; `NPM_CONFIG_PREFIX` |
| `java21` | Temurin 21 via SDKMAN med sjekksum, `JAVA_HOME`; bootstrap som 1.1.2 Del B §1.8 |
| `java17`, `java25` | Tilleggs-JDK-er for toolchain-valg |
| `kotlin`, `maven`, `gradle` | via SDKMAN |
| `python312` | Python 3.12 + pip + venv; `PIP_INDEX_URL` fra mirrors |
| `go123` | Go med `GOPROXY` fra mirrors hvis satt |
| `dotnet8` | .NET SDK |
| `xvfb` | Virtuell X-skjerm (Xvfb startes av entrypointet på `$DISPLAY`), GTK/Qt/X11-biblioteker, Mesa programvare-OpenGL, fonter, `scrot` og `xdotool`; GUI-programmer (JavaFX, Swing, GTK, Qt, Tk, Electron) kan kjøres og skjermbilder tas. `image/fragments/xvfb.md` forteller agenten hvordan |
| `chromium` | Debians Chromium med `--no-sandbox`-innpakning for sideskjermbilder og Playwright/Puppeteer, som ikke får laste ned egne nettlesere gjennom proxyen |

Agentens `image.Containerfile` legges alltid sist og forutsetter `node22`
når agenten er npm-distribuert (adapteren erklærer `"requires": ["node22"]`
i `agent.json`; `render` legger fragmentet til automatisk).

Ett image bygges per aktivert agent, fordi agentene ellers ville dele et
image med hverandres binærer. Bygg deler lag opp til agentfragmentet.

### 7.3 Felles stakk (`docker-compose.yml`)

To tjenester, som 1.1.2 Del B §2.2–2.3 med disse endringene:

- `proxy` får statisk IP `<prefix>.5` på `internal` (så samtalecontainere
  ikke er avhengige av DNS-aliaser fra compose).
- `gateway` (`<prefix>.10`) bygges fra `gateway/Containerfile` (kap. 8.10) med
  `USER_UID`/`USER_GID` fra `sandbox.config`; `gateway/plugins/` monteres
  read-only som `/app/plugins`. Minnegrense `${GATEWAY_MEM_LIMIT:-384m}`.
- Ingen `copilot`-tjeneste. `doctor` feiler hvis compose-filen inneholder
  en tjeneste med `/work`-mount (videreført sikkerhetssjekk fra 1.1.2 kap. 4.2).
- Nettverk `internal` (`internal: true`, `<prefix>.0/24`) og `external`.
  Compose-prosjektnavn = sandkassemappens navn; nettverksnavnet
  `<project>_internal` brukes av `session start`.

### 7.4 Squid

`proxy/squid.conf` er 1.1.2 Del B §3.2 uendret i regelrekkefølge:
blocklist → gateway-hosts (kun ikke-CONNECT) → Ollama → `private_dst` deny →
allowlist CONNECT 443 → allowlist plain → deny all. Eneste endringer:

- `cache_peer` og `http_port` bruker `<prefix>`.
- `allowed-domains.txt` har tre seksjoner (kap. 4.5).
- Access-loggen skrives til stdout og leses av `domain blocked` via
  `podman logs`. Formatet er Squids standard; parseren i `core.stack`
  gjenkjenner `TCP_DENIED/403`, `/407`, `/451` og trekker ut verten fra
  `CONNECT host:port` eller URL.

### 7.5 Subnett

Som 1.1.2 Del A «Network configuration», men registeret flyttes fra
`~/.cache/cplt-sndbx/subnets.txt` til
`${XDG_STATE_HOME:-$HOME/.local/state}/lyngvi/subnets.txt` (samme
format), og kandidatpoolen utvides til `172.31.240–254` og `172.30.240–254`
pluss `10.200.0–15` for maskiner der `172.x` er i bruk av VPN. `doctor`
sjekker kollisjon både mot registeret og mot `podman network ls`.

### 7.6 macOS og Linux

Alle plattformforskjeller ligger i pakken `core.runtime` (`Platform`):

| Tema | macOS | Linux |
|---|---|---|
| Podman | `podman machine` må kjøre; `resources machine` leser `podman machine inspect` | Rootless podman direkte; `resources machine` leser `nproc`, `/proc/meminfo`, `df` |
| UID/GID | `keep-id` i maskinen; Podman mapper til hostbruker | `keep-id` rett mot hostbruker |
| SELinux `:z` | Ignoreres | Nødvendig på Fedora/RHEL; harmløst ellers |
| `host.containers.internal` | Fungerer i maskinen | Krever `--add-host host.containers.internal:host-gateway` i `session start` |
| CA-eksport | `scripts/export-ca-certs.sh` bruker `security find-certificate` med navn fra profilen | Kopierer fra `/etc/ssl/certs` eller sti angitt i profilen |
| Klippebord i terminal | Dokumentert `Fn`-tips beholdes i README, macOS-seksjon | Ikke relevant |
| Stier | `/opt/homebrew/bin`, `/usr/local/bin` og `/opt/podman/bin` legges til PATH av `ProcessRunner` (nødvendig når GUI-et startes fra Finder) | `~/.local/bin` |

### 7.7 CI

Som 1.1.2 Del B §9–10 (digestsjekk, multi-arch, Trivy, Dependabot), utvidet
med:

- **JUnit på kjernen** uten Podman: konfig, generering, patch-klassifisering
  mot innspilte diffs, `session new`/`review`/`apply` mot midlertidige
  git-repoer på disk, adaptere mot mock-prosesser, låsing.
- **Ende-til-ende** i en Linux-runner med rootless Podman: `init → build →
  up → session new → ask (mock-adapter) → review → apply → down` via
  CLI-bildet mot et testrepo, med JSON-skjema-validering av all output.
- **GUI:** `mvn test` med JavaFX headless (Monocle), inkludert
  `FxmlBindingTest` og tester av tjenestelaget mot kjernen med mock-adapter.
- `shellcheck` på `setup.sh`, adaptere og `scripts/`.
- **Pakking:** jlink-bilde per plattform, jpackage for GUI, sjekksummer og
  utgivelsesartefakter.

En **mock-adapter** (`core.agents.MockAdapter`, og `agents/mock/adapter.sh`
som ekstern variant) følger med for tester: den svarer deterministisk,
gjør en kjent filendring i `/work/<repo>` og sender en fullstendig
hendelsesstrøm. Den eksterne varianten tester `ProcessAdapter`.

### 7.8 Sikkerhetsherding, oppsummert

Uendret fra 1.1.2 kap. 5 med disse presiseringene:

| Krav | 2.0 |
|---|---|
| Kun internt nett | Per-samtale container har kun `<project>_internal` |
| Allowlist + blocklist | Generert fra profil + agent + bruker; blocklist først |
| `cap_drop: ALL`, `no-new-privileges` | På alle containere inkl. per-samtale |
| Uprivilegert bruker | `agent` med hostens UID |
| Kopiert arbeidskopi | Uendret |
| Validert uttak, tre bøtter | I `core.delivery`; eneste implementasjon |
| Aldri `main` | `protectedBranches` fra profil + repoets default-gren |
| PAT-isolering | Gateway uendret |
| Pinnede images | Basisimage, Squid, gateway-base; agentversjon pinnet i fragment |
| **Nytt:** samtaleisolering | Én container per samtale; ingen delt `/work` |
| **Nytt:** ingen token i `.env` som standard | Legitimasjon kun i `state/agents/<id>/` |

Kjente begrensninger som dokumenteres: agentlegitimasjon i containeren
(6.6); samtalecontainere deler ett internt nett og kan nå hverandres
lytteporter hvis agenten starter en tjeneste (lav risiko; ingen tjenester
lytter som standard).

---

## 8. Gateway og plugin-system

### 8.1 Rolle

Gatewayen er den eneste veien fra sandkassen til systemer som krever
legitimasjon. Kjøretiden er generisk og vet ingenting om noe bestemt
system. Alt som er systemspesifikt (URL-former, ID-oppløsning,
tekstkonvertering, hvilke stier som er trygge) ligger i **plugins**, som
er jar-filer. Kodebasen leveres uten konfigurerte systemer; en profilpakke
legger til det organisasjonen trenger, inkludert eventuelle plugin-jar-er.

Egenskapene fra 1.1.2 Del C videreføres: manifest som sannhetskilde,
ruting på vertsnavn og stiprefiks, allowlist med hot-reload,
origin-pinning, read-only som standard, `/healthz`, maskert logging, og
tester som starter en ekte lytter med stubbet upstream.

### 8.2 Moduler og begreper

| Modul | Innhold | Avhengigheter |
|---|---|---|
| `lyngvi-gateway-api` | Plugin-grensesnittene (`GatewayPlugin`, `PluginContext`, `Route`, `Upstream`, `AllowlistResolver`) og manifestrecords (`Manifest`, `Instance`, `SecretSpec`, `AllowlistSpec`). Brukes av kjernen på hosten *og* av gatewayen i containeren, så manifest, normalisering og maskering finnes bare ett sted. | Ingen (ren JDK) |
| `lyngvi-gateway` | Kjøretiden: lyttere, ruting, hemmeligheter, allowlist, innebygd `http`-plugin, `/healthz`, lasting av plugins. Pakkes som `gateway.jar` og kjører i containeren. | `gateway-api`, Jackson |
| `lyngvi-gateway-plugins` | Referanseplugins `confluence` og `jira` som separate jar-er. | `gateway-api` |

| Begrep | Betydning | Hvor |
|---|---|---|
| **Plugin-type** | En implementasjon av `GatewayPlugin` som vet hvordan man snakker med én *klasse* av systemer | En jar i `gateway/plugins/`, eller den innebygde `http` |
| **Instans** | Ett konfigurert system: id, plugin-type, vertsnavn, base-URL-variabel, hemmelighet, allowlist, plugin-spesifikk konfig | Én oppføring i `gateway/gateway.manifest.json` |

Én plugin-type kan ha mange instanser.

### 8.3 Manifest

```json
{
  "schemaVersion": 2,
  "ipSuffix": 10,
  "instances": [
    {
      "id": "docs",
      "plugin": "http",
      "label": "Internal docs",
      "hostname": "docs.internal.example",
      "hostAliases": [],
      "baseUrlEnv": "DOCS_BASE_URL",
      "secret": { "env": "DOCS_TOKEN", "scheme": "bearer" },
      "allowlist": { "file": "docs.txt", "mode": "path-prefix", "label": "documents" },
      "capabilities": ["read"],
      "config": {
        "methods": ["GET"],
        "pathAllow": ["^/api/v1/pages/[0-9]+$", "^/api/v1/search$"],
        "queryAllow": ["q", "limit", "start"],
        "forwardRequestHeaders": ["accept"],
        "maxResponseBytes": 2000000,
        "timeoutMs": 15000
      }
    },
    {
      "id": "wiki",
      "plugin": "confluence",
      "label": "Wiki",
      "hostname": "wiki.internal.example",
      "hostAliases": ["wiki"],
      "baseUrlEnv": "WIKI_BASE_URL",
      "secret": { "env": "WIKI_TOKEN", "scheme": "bearer" },
      "allowlist": { "file": "wiki.txt", "label": "pages" },
      "capabilities": ["read"],
      "config": { "restBase": "/rest/api" }
    }
  ]
}
```

| Felt | Betydning |
|---|---|
| `id` | Instansens navn. Brukes i stiprefiks `/<id>/…`, i `setup.sh gateway …`, i filnavn under `secrets/` og `allowlists/`, og i miljøvariabelen `<ID>_GATEWAY_URL` i sandkassen. `^[a-z][a-z0-9-]*$`. |
| `plugin` | Plugin-type. `http` er innebygd; alt annet må finnes som en jar i `gateway/plugins/` som erklærer typen. |
| `hostname`, `hostAliases` | Vertsnavn som rutes til instansen. Går inn i sertifikatets SAN, Squids `gateway-hosts.txt`, og sandkassens `--add-host`/`NO_PROXY`. |
| `baseUrlEnv` | Navn på miljøvariabelen (i `secrets/<id>.env`) som gir upstream-origin. Ingen standardverdi; mangler den, starter ikke instansen. |
| `secret.env` | Navn på miljøvariabelen med hemmeligheten. `<env>_FILE` støttes. |
| `secret.scheme` | `bearer`, `basic` (verdi `bruker:passord`), `header:<Navn>` eller `query:<navn>`. Kjøretiden setter headeren; plugin ser aldri verdien. |
| `allowlist.file` | Fil under `gateway/allowlists/`. Tolkning bestemmes av pluginens `AllowlistResolver` eller `allowlist.mode` (`http`). |
| `allowlist.label` | Substantiv til CLI/GUI («pages», «issues», «documents»). |
| `capabilities` | `["read"]` som standard. `"write"` krever i tillegg at `id` står i profilens `gateway.allowWrite`. |
| `config` | Plugin-spesifikk. Deserialiseres til pluginens `configType()` med Jackson; ukjente felt avvises. |

Manifestrecordene i `gateway-api` er de samme klassene kjernen bruker i
`core.gateway`, så `gateway instance add` på hosten og lasting i containeren
kan ikke tolke manifestet ulikt.

### 8.4 Kjøretidens garantier

Disse gjelder for alle instanser, uansett plugin, og kan ikke skrus av fra
en plugin:

1. **Ruting.** Vertstabellen bygges fra manifestet og fryses. Ukjent
   vertsnavn gir `404` uten upstream-kall. Stiprefiks `/<id>/…` fungerer
   mot gatewayens eget navn.
2. **Metoder.** Kun `GET` (og `HEAD`) med mindre instansen har `"write"`
   i `capabilities` *og* profilen tillater det. Selv da er bare metodene
   ruten eller `config.methods` erklærer tillatt. Alt annet gir `405
   {error:"method not allowed", detail:"this gateway instance is read-only"}`.
3. **Upstream.** Plugin får et `Upstream`-objekt som er låst til
   `baseUrl`-origin og `restBase`. Stier som forsøker å rømme (`..`,
   absolutt URL, annet vertsnavn) avvises. Hemmeligheten injiseres av
   kjøretiden i det objektet; den finnes ikke som streng noe sted plugin
   kan lese. Tidsavbrudd og maksimal responsstørrelse håndheves av
   kjøretiden.
4. **Allowlist.** Kjøretiden leser filen ved hvert oppslag (hot-reload),
   plugin leverer `AllowlistResolver`. Ruter som erklærer `allowlistKey`
   får allowlisten håndhevet *før* handleren kjører; et avslag (`403`)
   skjer uten upstream-kall. Tom allowlist betyr «nekt alt».
5. **Svar.** `cache-control: no-store` alltid. `set-cookie` og
   hop-by-hop-headere fjernes. Størrelsesgrense.
6. **Logging.** Maskert. `Authorization`-verdier, hemmeligheter og
   `*_TOKEN`-variabler logges aldri. Samme `Secrets.mask()` som kjernen.
7. **Feilisolering.** En instans som ikke laster (manglende variabel,
   ugyldig konfig, plugin som mangler eller har feil `apiVersion`) stopper
   ikke de andre. `/healthz` viser den som `configured: false` med årsak.
   En plugin-jar som kaster ved lasting logges og hoppes over.
8. **Valgfri felles port.** `GATEWAY_TOKEN` som i 1.1.2.

### 8.5 Innebygd plugin `http`

Deklarativ. Dekker «ett REST-API med statisk token og et kjent sett stier».

| `config`-felt | Betydning | Standard |
|---|---|---|
| `restBase` | Prefiks som legges foran alle stier mot upstream | `""` |
| `methods` | Tillatte metoder (begrenses av 8.4 punkt 2) | `["GET"]` |
| `pathAllow` | Liste av regex; forespørselens sti (etter `restBase`) må matche minst én | påkrevd |
| `queryAllow` | Tillatte query-parametre; andre fjernes | alle |
| `forwardRequestHeaders` | Klientheadere som videresendes | `["accept"]` |
| `rewrite` | `{from, to}` regex-omskriving av sti før upstream | ingen |
| `maxResponseBytes`, `timeoutMs` | Grenser | 2 MB, 15 s |

`allowlist.mode` for `http`:

| Modus | Oppføring i filen | Håndheves som |
|---|---|---|
| `none` | (fil ignoreres) | Kun `pathAllow` |
| `path-prefix` | `/api/v1/pages/123` | Sti må starte med en oppføring |
| `path-exact` | `/api/v1/pages/123` | Sti må være lik en oppføring |
| `path-regex` | `^/api/v1/pages/1[0-9]+$` | Sti må matche en oppføring |
| `query:<param>` | `123` | Verdien av `<param>` må stå i listen |

Svar sendes uendret videre (JSON eller annet). Ingen tekstkonvertering.
Med `capabilities: ["write"]` og `methods: ["POST"]` er `http`-pluginen
også det som trengs for token-injeksjon mot en modelleverandør (kap. 11);
det er da en ren konfigurasjonsendring.

### 8.6 Plugins som jar-filer

En plugin-type er en jar i `gateway/plugins/` som:

1. avhenger av `lyngvi-gateway-api` (provided-scope; kjøretiden har den),
2. implementerer `GatewayPlugin`,
3. erklærer implementasjonen i
   `META-INF/services/no.syntakserror.lyngvi.gateway.api.GatewayPlugin`.

Grensesnittet (forkortet; fullstendig javadoc i modulen):

```java
public interface GatewayPlugin {
    int API_VERSION = 1;

    String type();                                   // "confluence"
    default String label() { return type(); }
    default String allowlistLabel() { return "entries"; }
    default int apiVersion() { return API_VERSION; }

    /** Konfigurasjonstype for instance.config; deserialiseres av kjøretiden, ukjente felt avvises. */
    default Class<?> configType() { return Void.class; }

    /** Allowlist-semantikk: rå oppføring -> kanonisk nøkkel, ev. med oppslag via Upstream. Kjøretiden cacher 5 min. */
    AllowlistResolver allowlist();

    /** Rutene. Kjøretiden håndhever metode, allowlist (via allowlistKey) og grenser rundt handleren. */
    List<Route> routes();

    /** Valgfritt: gjenkjenn nettleser-URL-er limt inn av agenten og oversett til en rute. */
    default Optional<RouteTarget> parseBrowserUrl(URI url) { return Optional.empty(); }

    /** Valgfritt: helsesjekk mot upstream for /healthz?deep=1. */
    default HealthProbe health() { return HealthProbe.none(); }
}

public record Route(String method, String pathPattern, Function<Request, String> allowlistKey, Handler handler) {
    public static Route get(String pattern, Handler h) { … }
    public static Route get(String pattern, Function<Request, String> key, Handler h) { … }
}

public interface Handler { Response handle(Request req, PluginContext ctx) throws Exception; }
```

`PluginContext` gir:

| Metode | Innhold |
|---|---|
| `instance()` | `Instance` fra manifestet (id, label, hostname, capabilities) |
| `config()` | Deserialisert `instance.config` av typen `configType()` |
| `upstream()` | `get(path, params)`, `request(method, path, body, headers)` (kun metoder instansen tillater), `getRawLocation(path)` for redirect-oppslag. Origin-låst, hemmelighet injisert. |
| `allowlist()` | `allows(key)`, `describe()` |
| `text()` | `htmlToText`, `wikiMarkupToText`, `stripTags` (portert fra 1.1.2 `core/text.js`) |
| `json()` | Lese og skrive JSON (Jackson `ObjectMapper` fra kjøretiden, versjon dokumentert i `gateway-api`) |
| `log()` | `info`, `warn`, `error` (maskert) |

**Lasting.** Ved oppstart leses `/app/plugins/*.jar` (mountet fra
`gateway/plugins/`). Hver jar får sin egen `URLClassLoader` med
kjøretidens klasselaster som forelder, og `ServiceLoader.load(GatewayPlugin.class, loader)`
gir typene jaren tilbyr. To jar-er som erklærer samme `type()` er en feil
som logges; den første vinner. `apiVersion()` som ikke støttes gjør at
instanser av typen ikke laster; resten starter. Egen klasselaster per jar
isolerer *avhengigheter* (to plugins kan bruke ulike biblioteksversjoner),
ikke sikkerhet.

**Tillit.** Plugins kjører in-process og er **betrodd kode på linje med
`setup.sh`**. Garantiene i 8.4 beskytter mot *sandkassen*, ikke mot en
ondsinnet plugin-forfatter. Plugins fra profilpakker skal gjennomgås som
enhver annen kodeavhengighet. Signaturverifisering av jar-er er fremtidig
arbeid (kap. 11).

**Bygging.** En plugin bygges med vanlig Maven eller Gradle mot
`lyngvi-gateway-api` og shader inn egne avhengigheter utover JDK og
Jackson. `docs/gateway-plugins.md` har en komplett minimal plugin som
mal.

### 8.7 Medfølgende plugins

| Type | Slag | Innhold |
|---|---|---|
| `http` | innebygd i kjøretiden | Deklarativ, kap. 8.5 |
| `confluence` | jar, referanse | Portert fra 1.1.2: numerisk id, nettleser-URL-former (`/spaces/<KEY>/pages/<id>`, `viewpage.action?pageId=`, `/display/<KEY>/<Title>`, `/x/<kort>`), tittel-oppslag, søk med etterfiltrering, HTML → tekst. Ingen standard base-URL. |
| `jira` | jar, referanse | Portert fra 1.1.2: nøkkelnormalisering, `/browse/KEY-1`-former, JQL innsnevret til allowlisten før upstream og filtrert etter, kommentarer, wiki-markup → tekst. Ingen standard base-URL. |

De to jar-ene bygges av `lyngvi-gateway-plugins` og legges i
`gateway/plugins/` av `init`, men er **ikke aktivert** i standardprofilen;
en profilpakke aktiverer dem ved å legge til instanser. All 1.1.2-spesifikk
oppførsel (legacy-vertsnavn, legacy `/healthz`-felt, `wiki`/`jira` som
CLI-aliaser) er fjernet.

### 8.8 CLI og GUI

- `setup.sh gateway …` opererer på instans-id-er (kap. 4.3). `gateway list --json`
  gir per instans: `{id, plugin, label, hostname, hostAliases, secretConfigured,
  allowlist: {file, label, count}, capabilities, healthy}`.
- `gateway plugin list` leser `gateway/plugins/*.jar` på hosten med samme
  `ServiceLoader`-mekanisme (kjernen avhenger av `gateway-api`) og viser
  `{type, label, apiVersion, jar}`. `gateway plugin add <jar|url>` kopierer
  jaren inn etter å ha verifisert at den erklærer minst én `GatewayPlugin`
  med støttet `apiVersion`. `gateway plugin remove <type>` nekter hvis en
  instans bruker typen.
- `gateway instance add` skriver manifestet, oppretter tom allowlist og
  `secrets/<id>.env.example` med `baseUrlEnv` og `secret.env`, og kjører
  `rebuild-config` (nytt sertifikat, `gateway-hosts.txt`). Gatewayen må
  startes på nytt for å laste nye plugins eller instanser; kommandoen sier fra.
- GUI-ets `GatewayPane` bygges fra kjernens gateway-tjeneste, én fane per
  instans, med samme kontroller som i 1.1.2 (hemmelighet, allowlist).
  «Legg til system…» viser plugin-typer fra `gateway plugin list` og
  rendrer felter fra pluginens `configType()` (strenger, tall, boolske,
  lister av strenger, via records og Jackson). Mer komplekse typer
  redigeres som JSON i en tekstboks.

### 8.9 `/healthz`

Unified-format som 1.1.2 Del C, med `adapters` omdøpt til `instances` og
uten legacy-varianten. Per instans: `{id, plugin, label, hostnames,
configured, reason?, baseUrl, allowlist: {enforced, count},
upstream?: {ok, status, reason?}}`. I tillegg `plugins: [{type, label,
apiVersion, jar}]`.

### 8.10 Kjøretid og container

- **Server:** `com.sun.net.httpserver.HttpServer` på `PORT` (8080) og
  `HTTP_ALT_PORT` (80), `HttpsServer` på `TLS_PORT` (443) med sertifikatet
  fra `gateway/certs/`. PEM leses direkte: nøkkelen er PKCS#8
  (`PKCS8EncodedKeySpec`), sertifikatkjeden via `CertificateFactory`.
  Virtuelle tråder som executor.
- **Klient:** `java.net.http.HttpClient` med tidsavbrudd per instans og
  CA-bundle fra `/certs` (organisasjonens CA) lagt til trust store ved
  oppstart.
- **Image:** `gateway/Containerfile` er multi-stage. Byggetrinn på
  `eclipse-temurin:25-jdk` (pinnet digest) kjører `jlink` med modulene
  `java.base`, `java.net.http`, `jdk.httpserver`, `jdk.crypto.ec` og
  `java.xml`; sluttbilde på `debian:bookworm-slim` (pinnet) kopierer
  kjøretiden og `gateway.jar`, kjører som `gateway` (UID fra build-arg),
  read-only filsystem, `tmpfs` på `/tmp`, `cap_drop: ALL` +
  `NET_BIND_SERVICE`, `no-new-privileges`. `/app/plugins` mountes
  read-only fra `gateway/plugins/`.
- **Ressurser:** `-Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`;
  container-grense `${GATEWAY_MEM_LIMIT:-384m}`.
- **Helsesjekk:** `HEALTHCHECK` med `java -cp gateway.jar …HealthProbe` mot
  `http://127.0.0.1:8080/healthz` (ingen curl i imaget).
- **Miljøvariabler:** som 1.1.2 Del C (`PORT`, `HTTP_ALT_PORT`, `TLS_PORT`,
  `TLS_CERT_FILE`, `TLS_KEY_FILE`, `GATEWAY_TOKEN`, `UPSTREAM_TIMEOUT_MS`,
  `MAX_LIMIT`, `ALLOWLIST_DIR`, `ALLOWLIST_ENFORCED`, `GATEWAY_MANIFEST`,
  `CA_CERT_DIR`) pluss `PLUGIN_DIR` (`/app/plugins`).

### 8.11 Testing

- **Testharness** i `lyngvi-gateway`: `GatewayHarness.start(manifest, secrets, allowlists, plugins)`
  starter kjøretiden på en tilfeldig port med en stubbet `Upstream`-fabrikk
  og gir en klient som setter riktig `Host`. Brukes av kjøretidens egne
  tester, av referansepluginene og av tredjeparts plugin-prosjekter
  (harnessen publiseres som test-jar).
- **Kjøretidstester** (portert fra 1.1.2 `gateway.test.js` og utvidet):
  ukjent host → 404 uten upstream; 405 på non-GET; allowlist før upstream;
  søkefiltrering; origin-pinning; hemmelighet ikke synlig for plugin;
  `http`-plugin med hver allowlist-modus og hvert secret-scheme;
  `capabilities: write` avvist uten `allowWrite`; plugin-jar med feil
  `apiVersion` hoppes over; to jar-er med samme type.
- **Plugin-tester** i `lyngvi-gateway-plugins` mot harnessen.
- **Ende-til-ende** (Podman): `gateway instance add --plugin http …` +
  `.env` → `curl` fra sandkassen får 200 på tillatt sti og 403 på
  ikke-tillatt; en egenbygd plugin-jar lagt i `gateway/plugins/` svarer
  etter `gateway restart`.

---

## 9. JavaFX GUI

### 9.1 Prinsipper

1. GUI-et endrer aldri tilstand selv. Alle knapper som gjør noe kaller en
   operasjon i kjernens `api`-pakke, den samme CLI-en bruker.
2. GUI-et leser filer kun for visning: arbeidskopien i filpanelet, logger.
   Samtaledata hentes fra kjernen, ikke ved å lese samtalefilen selv.
   Skriving til arbeidskopien fra filpanelet er brukerens egen redigering
   og tillates (den er utenfor kontrakten, som i 1.1.2).
3. Alle langvarige kall er asynkrone og strømmende; UI-tråden blokkeres
   aldri. Kjernen kjører på virtuelle tråder; GUI-et marshaller til
   FX-tråden.
4. Ingen brukervendt tekst i kode. Samme `ResourceBundle` som kjernen.
5. Tjenestelaget testes mot kjernen med mock-adapter og midlertidige
   repoer, ikke mot innspilte JSON-svar.

### 9.2 Lagdeling

```
view/          FXML + controllere + gjenbrukte komponenter (uendret fra 1.1.2 der mulig)
viewmodel/     tynt lag: kaller core.api, marshaller til FX-tråden, holder observable state
  SessionViewModel   new/list/show/start/stop/ask/suggest/review/apply/export/remove/gc
  StackViewModel     up/down/status/logs/doctor/build
  ConfigViewModel    repo/domain/resources/gateway/agent/profile
  AgentRunBinder     abonnerer på AgentEvent, bygger ChatMessage + WorkLog live
  Settings, AppLog, Theme, ProblemReport (beholdes)
model/         kjernens contract-records brukes direkte; ingen egne kopier
```

Kjernen eier `ProcessRunner`, `PodmanService`-ekvivalenten, låsing,
samtalelagring og alt under. GUI-modulen inneholder ingen `git`- eller
`podman`-kall; CI håndhever det med en arkitekturtest (ArchUnit) som
forbyr `ProcessBuilder` utenfor kjernen.

Fjernes fra GUI-modulen (flyttet til kjernen eller erstattet):
`SandboxService`, `PodmanService`, `ContainerPool`, `SessionWorkspaceService`,
`CopyFilter`, `CopilotService`, `CopilotJsonParser`, `DeliveryService`,
`PatchBuilder`, `PatchValidator`, `HostGitService`, `BranchTarget`,
`ConversationStore`, `BlockedRequestMonitor`/`BlockedRequestParser`,
`GatewayService`, `ResourceAdvice`/`ResourceSizes`, `Changelog`,
`SandboxTemplate`, `ProcessRunner`. Det som er verdt å beholde av logikken
(f.eks. ANSI-stripping, ressursformatering, changelog-parsing) porteres
til kjernen med testene sine.

Beholdes uendret eller nesten uendret: `MarkdownArea`, `WorkLogPane`,
`DiffArea`, `DiffPaneBuilder`, `CodeEditor`, `SyntaxHighlighter`,
`ToolOutputArea`, `GrowingTextArea`, `ConsoleView`, `Dialogs`,
`WelcomeDialog`, `ChangelogDialog`, `GatewayPane`, `UsagePaneBuilder`,
`FilesController`, `Theme`, `AppLog`, `Settings`, `ProblemReport`,
`AttachmentHelper`, alle CSS-temaer.

### 9.3 Vinduer og faner

Strukturen fra 1.1.2 Del E beholdes (toolbar, nav-rail, skjulte faner,
filpanel). Der tabellen nevner en CLI-kommando, menes kjernens
tilsvarende operasjon. Endringer:

| Sted | Endring |
|---|---|
| Oppstart (ny) | Et splash-vindu vises mens appen starter: merket på en lys skive (navyen i logoen leses ikke mot mørkt tema uten), navnet, taglinjen og versjonen på et kort i temaet innstillingene holder, uten vindusramme og sentrert på hovedskjermen. Taglinjen (`gui.splash.tagline`) vises på språket til sandkassen som sist var åpen — språket er en sandkasseinnstilling, og å åpne en er nettopp arbeidet splashen dekker over, så GUI-innstillingene husker språket (`language`) hver gang åpen sandkasse endres. Aller første oppstart er på engelsk. Det står i minst ti sekunder — en oppstart som er raskere enn det venter ut resten — og tones så ut over hovedvinduet. En lukk-knapp øverst til høyre tar det ned med én gang, og det samme gjør Escape eller et klikk hvor som helst på kortet — Escape virker også mens hovedvinduet, som vises under splashen, har tastaturet. Førstegangsdialogene og migreringstilbudet kommer først etterpå uansett hvilken vei det går, siden de er modale og splashen ligger øverst. |
| Sandkassevelger i verktøylinjen (ny) | Stien øverst til venstre er en nedtrekksmeny, ikke en etikett: hver sandkasse maskinen har arbeidet med (de tolv sist åpnede, husket i `~/.lyngvi-gui.properties` som `recent.sandbox.<n>`), den åpne avmerket, og en mappe som er borte utelatt. Å velge en viser den i dette vinduet — samtalelisten, filpanelet og konsollet tømmes, logg- og trafikkfølging stopper, og samtaler som jobber spørres det om først; de fortsetter i containerne sine, vinduet slutter bare å følge dem. Under «Åpne i nytt vindu» åpner samme liste sandkassen i et eget Lyngvi-vindu i stedet. |
| Flere vinduer (ny) | Ett vindu per sandkasse, så flere sandkasser kan brukes samtidig. Hvert vindu har sin egen `SandboxContext`, sine egne viewmodeller og sitt eget konsoll (`MainWindow`); vinduene deler bare GUI-innstillingene og FX-tråden. Vindustittelen er «Lyngvi — ‹mappenavn›», bare det første vinduets geometri huskes (de andre trappes ned og til høyre fra vinduet de ble åpnet fra), og lukkingsadvarselen gjelder bare samtalene i vinduet som lukkes. En sandkasse som allerede har et vindu åpnes aldri to ganger — valget hever vinduet den er i. |
| Filpanel i eget vindu (ny) | Filpanelet er ikke lenger en kolonne inne i hovedvinduet, men et vindu for seg (`FilesWindow`), ett per hovedvindu. «⧉ Filer» i verktøylinjen og ⌘/Ctrl+B åpner og lukker det — snarveien virker i begge vinduene — og brukeren gir det selv størrelse og plassering: geometrien huskes i `~/.lyngvi-gui.properties` (`filesWindow`) og vinduet åpnes der neste gang, første gang ved siden av hovedvinduet på samme skjerm. Om det sto åpent da appen ble forlatt (`filesWindow.open`) kommer det opp igjen med vinduet; uten innstillingen er det lukket. Vinduet har ingen eiervindu — et eid vindu holdes alltid foran eieren sin, som er det ene et uttrukket panel ikke kan — så hovedvinduet lukker det når det selv lukkes, og tittelen er «Filer — ‹mappenavn›» slik at to av dem kan skilles. Innholdet er `FilesController` uendret: treet, redigering, lagring, Rå/Forhåndsvis og samtalen som er valgt i vinduet det hører til. |
| Syntaksfarger i filpanelet (ny) | En kildefil i filpanelet åpnes med farger (`CodeView`) i stedet for som flat tekst, med «Rå» ett klikk unna — samme toveisvalg som markdown har, og valget huskes for økten. Fargeleggingen er en egen skanner (`SyntaxScanner`), ikke et bibliotek: RichTextFX, som 1.1.2 brukte, og hele stakken under den er automatiske moduler, og `jlink` kan ikke lenke dem i det hele tatt. Visningen er en virtualisert liste med én linje per rad, slik diffen er, og tekstvalget er det samme (`LineSelection`, omdøpt fra `DiffSelection` og nå generisk over elementtypen): kopiering gir koden uten linjenummer. Bare linjene på skjermen tokeniseres; det som regnes ut for hele filen er én `int` per linje — tilstanden linjen begynner i — så en blokkkommentar som åpnet tusen linjer over synsfeltet fortsatt er en kommentar. Visningen er skrivebeskyttet, siden JavaFX' egne tekstkontroller ikke kan farge deler av innholdet; editoren er der skriving og lagring skjer. En fil ingen språk vedkjenner seg åpnes som ren tekst uten valgknapper — en gjetning er verre enn ingen farge. |
| Språk som plugin, og «Syntaksfarger»-siden (ny) | Et språk er en `.syntax`-fil (`Syntax`, properties-format): kommentarmarkører, strengavgrensere, flerlinjestrenger, annotasjonsprefikser og ordlistene `keywords`, `types`, `constants`. Femten følger med som ressurser i modulen (listet i `syntax/index`, siden en jar ikke kan spørres om innholdet i en mappe); brukerens egne ligger i `~/.lyngvi-syntax/`, og en fil der med samme navn som en medfølgende erstatter den helt, slik at leverte regler kan rettes uten et bygg. Ingen jar og ingen kode: gateway-pluginene kjører i sidecaren inne i containeren, mens GUI-et kjører på hosten med brukerens egne rettigheter, og en fargeplan er ingen grunn til å laste kode der. Nytt punkt i ikonmenyen, «Syntaksfarger», bygget som Skills-siden: listen med språk, kilde og filtyper til venstre, filen i editoren til høyre, og under den fargene reglene gir — et utdrag farget på nytt for hvert tastetrykk, med grunnen i klartekst når filen ikke kan leses (`unknown key: …`). «Nytt språk…» lager filen fra en mal, «Fjern min» sletter brukerens fil og lar den medfølgende komme tilbake. Siden er den eneste som virker uten en åpen sandkasse, siden reglene er brukerens og ikke sandkassens, og fargene er temaets (`-app-syntax-*` i begge paletter): et språk sier hva et ord *er*, aldri hvilken farge det har. Utenfor rekkevidde, med vilje: alt som trenger en parser — JSX, heredoc, kode inni interpolerte strenger, og taggbaserte språk som HTML og XML. |
| Filtreet starter lukket (ny) | Treet i filpanelet bygges med alle mapper lukket, arbeidskopiene og `output` inkludert, og brukeren åpner det den trenger. Tidligere ble hver mappe satt åpen når treet ble bygget, slik at en arbeidskopi av vanlig størrelse ble tusenvis av rader med `target/` og `node_modules/` utbrettet før den filen det var snakk om. Filteret er veien til en fil som ligger dypt: når det er noe i filterfeltet er bare treffene igjen i treet, og da settes mappene åpne så de ikke skjuler dem. En mappe brukeren åpner huskes (`FilesController.opened`, nøklet på elementets verdi, altså stien med roten foran), så en oppdatering, en lagring eller et filter som skrives og tømmes igjen setter treet tilbake slik brukeren forlot det i stedet for å lukke det. Byttet til en annen samtale — eller til en annen sandkasse — tømmer settet, og treet starter lukket igjen. |
| «Ny sandkasse»-veiviser (ny) | «Ny sandkasse…» er en veiviser, ikke ett skjema: seks sider — mappe og repoer (`init`, og så `rebuild-config`, som `init` ikke gjør og et bygg trenger), basisimage (`build`, med utdataene i dialogen), felles stakk (`up`, med tjenestene som kom opp), sjekk (`doctor`, med én linje per sjekk), agent (agentlisten og «Logg inn…», som åpner den vanlige innloggingsdialogen) og en oppsummering med «Start en samtale når denne lukkes». Det veiviseren etterlater er altså en sandkasse det går an å starte en samtale i, ikke en tom mappe. Én framoverknapp gjør sidens arbeid og går først videre når det er gjort; en side kan ikke forlates mens arbeidet pågår, så et svar som kommer tilbake finner alltid siden som startet det. Sjekk og agent er valgfrie og kan passeres — begge nås etterpå under «Sandkasse» og «Agenter», og hoppet over står i oppsummeringen. Rekkefølgen og knappelogikken ligger i `WizardFlow`, som er ren og enhetstestet. |
| Nav-rail «Skills» (ny) | Et eget punkt i ikonmenyen, ved siden av «Agenter», for ferdighetene sandkassen gir agenten (kap. 3.6). Siden er listen til venstre og én `SKILL.md` i editoren til høyre: en avkryssingsboks per skill som er hele «aktiv» — den lagres i det den klikkes, siden det å slå av en skill er en avgjørelse i seg selv og ikke noe som skal lagres etterpå — navnet, og beskrivelsen fra front matter (eller «kan ikke lastes som den står» for en skill med feil, med forklaringen under editoren). «Ny skill…» spør om navnet og lager mappen med en `SKILL.md` å fylle ut, som åpnes med én gang; «Fjern valgt» spør før mappen slettes. Editoren har Lagre (⌘/Ctrl+S) og Forkast, og spør før ulagret tekst forlates — også når listen oppdateres under føttene på den, for en oppdatering bytter ut radene og skal ikke ta editoren fra brukeren. Under editoren står hva en endring betyr: en lagret skill gjelder fra neste melding i alle samtaler, også de som kjører. Siden navngir også agentene som faktisk tar imot skills, og sier det når ingen av dem gjør det. Skills er per sandkasse, så listen tømmes når vinduet bytter sandkasse. |
| Nav-rail «Agentisk koding» | Samtalelisten viser agentikon og containerstatus (stoppet/kjører/jobber). Kontekstmeny får «Koble til repo…», «Stopp container» og «Fjern samtale». «Koble til repo…» viser de registrerte repoene samtalen ikke har fra før, med samme historikkvalg som «Ny samtale», og legger arbeidskopiene i samtalens volum mens den går (kap. 5.2.1). |
| «Ny samtale»-dialog | Tre grupper: hva samtalen gjelder (tittel, repoer), hvem som svarer (agent fra `agent list`, modell fra `agent models`), og — sammenfoldet, siden sandkassens standard nesten alltid er riktig — containeren (historikk, ressursgrenser, delte cacher). Repoene som var huket av sist huskes per sandkasse i GUI-innstillingene (`repos.last.<sandkassekatalog>`), så neste samtale starter fra samme valg; «ingen repoer» huskes også. Når dialogen lukkes med «Opprett», legger et kort seg over transkripsjonen med spinner, repoene som kopieres inn i `/work` og sekundene telt — først ett sekund inn i ventingen, siden en samtale uten arbeidskopier er ferdig før det og en spinner som blinker er verre enn ingen, og helt til samtalen er åpen; «Ny samtale» er deaktivert så lenge, så det er aldri to opprettelser i gang. Øverst i en åpnet samtale står én–to linjer om oppsettet: når økten ble laget, hvilken agent som svarer, og per arbeidskopi grenen og commiten den er tatt fra, hvor mye historikk som fulgte med og om hostens ukommitterte endringer er med. Linjene leses fra samtalefilens `base` (`SessionSetupLog`, ren og enhetstestet), så de står der hver gang samtalen åpnes og ikke bare rett etter at den ble laget. |
| Sandkasse → «Innlogging» | Blir «Agenter»: én rad per aktivert agent med status, «Logg inn», «Logg ut». Innlogging via OAuth viser URL og kode fra `agent login --json` og åpner nettleser. |
| Sandkasse → «Ressurser» | Standardgrenser + maskininfo fra `resources machine`, uendret UX. |
| Oppsett → «Profil» (ny) | Viser aktiv profilpakke, versjon, «Oppdater profil», «Bytt profil…». |
| Oppsett → «Verktøykjede» (ny) | Avkryssing av fragmenter; «Bygg på nytt». |
| «Hent inn»-dialog | Uendret UX (utgangspunkt, tre bøtter, filvalg, gren, melding, commit/stage, spinner for forslag), men datakilden er `session review --json`, og «Påfør» kaller `session apply`. Avviste filer vises med `reason` oversatt. |
| «Git Diff»-vindu | Datakilde `session diff --json` + `session diff --path` for innhold. Topptekst som 1.1.2 kap. 4.3. |
| «Trafikk» | Datakilde `domain blocked --follow`. «Tillat» kaller `domain add`. |
| «Copilot chat» (ren Q&A) | Blir «Spør agenten»: kjører `session ask` mot en flyktig samtale uten repoer (`session new --no-repos`), som fjernes med `session remove` ved lukking. |
| Versjonsknapp | `version --json` gir app-, sandkasse- og profilversjon; changelog derfra. |

FXML-kontrakten (`FxmlBindingTest`) beholdes. Alle `fx:id` fra 1.1.2 Del E
som fortsatt finnes beholder navn, så tester og CSS overlever.

### 9.4 Chat-flyt

1. Bruker sender melding → `SessionViewModel.ask(id, prompt, attachments)`
   → `api.SessionService.ask(...)`, som returnerer et `AgentRun` med
   `Flow.Publisher<AgentEvent>` og `cancel()`.
2. `AgentRunBinder` abonnerer, samler hendelser i batcher (maks 30
   UI-oppdateringer/s) og bygger live-boblen på FX-tråden: `text` →
   `MarkdownArea`, `reasoning` → «Tenker»-pill, `tool_start/tool_end` →
   `WorkLogPane`, `usage` → kontekst- og kostnadsetiketter.
3. «Stopp» kaller `cancel()`; kjernen sender SIGTERM til `podman exec`
   (eller til adapterprosessen) og avslutter med `done` og `cancelled`.
4. Ved `done` er samtalefilen allerede oppdatert av kjernen; GUI-et leser
   `SessionService.show(id)` og erstatter live-boblen med den lagrede
   meldingen.

### 9.5 Feilhåndtering

- Kjernen kaster `SandboxException` med `code` (samme koder som
  `contract/errors.json`) og `data`. GUI-et oversetter på kode.
  `SESSION_BUSY`, `PROTECTED_BRANCH`, `NO_CHANGES` (med alternativer) og
  `NOT_LOGGED_IN` får egne dialoger; resten vises generisk med «Vis
  detaljer» som viser `data.stderr`.
- `NOT_INITIALIZED`/`MIGRATION_REQUIRED` sender brukeren til
  oppstartsdialogen med «Migrer»-knapp.
- En `Flow` som avsluttes uten `done` behandles som `done` med `error`.

### 9.6 Tester

- Tjenestelaget testes mot kjernen med `MockAdapter` og midlertidige
  git-repoer; ingen Podman.
- `FxmlBindingTest` og `SeverityStyleTest` fra 1.1.2 videreføres.
- ArchUnit-test: ingen `ProcessBuilder`, ingen fil-skriving under `state/`
  fra GUI-modulen.
- Integrasjonstest (valgfri, krever Podman): kjører en hel samtale gjennom
  GUI-tjenestene mot en ekte container med mock-adapteren.

---

## 10. Akseptansekriterier

Alle punkter i 1.1.2 Del F.2 (S1–S16) og F.4 (U1–U9) gjelder, med disse
endringene og tilleggene.

**Sikkerhet**

| # | Kriterium | Verifikasjon |
|---|---|---|
| S5' | Agenten kjører som hostens UID, uten capabilities | `id`, `capsh --print` i containeren |
| S17 | To samtaler kan ikke se hverandres arbeidskopier | Fil skrevet i samtale A finnes ikke i samtale B; `ls /work` viser kun egne repoer |
| S18 | Uttak nekter mens agenten jobber | `session apply` under `ask` gir `SESSION_BUSY` |
| S19 | Avviste filer påføres aldri | `--include ../x` gir `REJECTED_FILE`; patch med `.git/hooks/pre-commit` vises som avvist og er ikke på hostens gren etter apply |
| S20 | Ingen agentlegitimasjon i `.env` eller compose | `grep` etter kjente variabelnavn i genererte filer gir ingen treff |
| S21 | `doctor` advarer ved GitHub-token + `github.com` allowlistet | |
| S22 | Arbeidskopiens `.git` inneholder aldri hooks, hostens konfig eller remotes, uansett `copy.history` | `ls /work/<repo>/.git/hooks` i containeren er tom eller kun `*.sample` fra tom mal; `git config --list --local` viser bare det `session new` satte; `git remote` er tom |
| S23 | `copy.history: none` gir ingen commits utover baseline | `git log --oneline` i kopien viser én commit |
| S24 | Ingenting agenten skriver ligger på hosten | `.sessions/<id>` har verken `work/` eller `output/`; `podman volume exists <container>-work`; containerens eneste skrivbare mount under `/work` er volumet |
| S26 | Opprydding rører aldri noe utenfor sandkassen | `clean --all --yes` med en fremmed container, et fremmed volum og et fremmed image til stede lar alle tre stå |
| S27 | Opprydding tar aldri arbeid som ikke er levert | `clean --work --older-than 0 --yes` lar arbeidskopien til en gammel samtale uten uttak stå; den går først med `--include-undelivered` |
| S28 | En container fjernet av `gc` tar ikke samtalen med seg | Etter `session gc` med utløpt `idleRemoveHours` finnes verken containeren eller noe tap: neste `ask` svarer, og arbeidskopien er uendret |
| S25 | Hooks og `core.fsmonitor` i arbeidskopien kjører aldri for oss | En `pre-commit` i kopien som skriver en fil kjører ikke under `review` eller `apply` (enhetstestet) |
| S29 | En eksport tar hele samtalen ut uten å påføre noe, og uten å ta med symlinker eller kopiens `.git/hooks` og `.git/config` | `session export` gir et arkiv med `EXPORT.txt`, arbeidskopien og dens `.git`, men uten en symlink i kopien og uten hooks/config; `review` etter eksporten viser samme filliste som før |

**Paritet** (erstatter F.3)

| # | Kriterium |
|---|---|
| P1' | Enhver tilstandsendring GUI-et gjør går gjennom en operasjon i kjernens `api`-pakke som også har en `setup.sh`-kommando (verifiseres med ArchUnit: ingen `ProcessBuilder` og ingen skriving under `state/` fra GUI-modulen) |
| P2' | En samtale ført fra terminalen har full meldingshistorikk i GUI-et, og omvendt |
| P3' | `session review` fra CLI og «Hent inn» i GUI viser identisk filliste og bøtter for samme samtale |

**Generalitet**

| # | Kriterium |
|---|---|
| G1 | Standardprofilen kjører en Node-samtale mot et testrepo uten redigering av noen fil |
| G2 | En profilpakke generert av `migrate` fra en eksisterende 1.1.2-installasjon reproduserer oppsettet (allowlist, gateway-instanser, verktøykjede, språk) uten endringer i kode |
| G6 | Kildekoden, malene og standardprofilen inneholder ingen vertsnavn, URL-er, sertifikatnavn eller identifikatorer som tilhører én bestemt organisasjon; CI kjører et grep-basert forbud mot en liste av kjente slike verdier |
| G7 | Et nytt system kan legges til i gatewayen med `gateway instance add --plugin http` og en `.env`-fil, uten kodeendring og uten omstart av stakken utover `gateway restart` |
| G8 | En plugin-jar bygget utenfor repoet mot `lyngvi-gateway-api`, lagt i `gateway/plugins/`, lastes av gatewayen og kan brukes som `--plugin <type>` uten endringer i produktet |
| G3 | Bytte av agent på en ny samtale krever kun `--agent`; ingen omstart av stakken |
| G4 | `LANG_UI=nb` og `LANG_UI=en` gir fullstendige oversettelser; manglende nøkler feiler CI |
| G5 | Samme installasjon fungerer på macOS (Apple Silicon) og Linux (x86_64, rootless Podman) |

---

## 11. Fremtidig arbeid (utenfor v1, designet inn)

- **Token-injeksjon for agentens egne API-kall.** En `http`-instans med
  `capabilities: ["write"]`, `methods: ["POST"]` og `pathAllow` for
  leverandørens meldingsendepunkt, med leverandørens vertsnavn som
  `hostname`. Sandkassen får en plassholder-legitimasjon; gatewayen bytter
  den ut. Da kan agentens legitimasjon flyttes fra `state/agents/<id>/`
  til `gateway/secrets/`. Forutsetter at agenten godtar plassholder eller
  base-URL-overstyring (Claude Code og OpenCode gjør det), og at
  OAuth-fornyelse løses (langlevd token eller fornyelse i gatewayen).
- **Skillsbibliotek på GitHub.** Et nettbasert bibliotek av ferdigheter
  (kap. 3.6) å hente fra i stedet for å skrive dem selv: et repo med én
  katalog per skill og et manifest, hentet gjennom proxyen som alt annet.
  Installering er en kopi inn i `<sandkasse>/skills/<navn>/` — det er hele
  modellen — og `skills.json` har skjemaversjon og én oppføring per skill
  nettopp for å kunne bære hvor en skill kom fra og hvilken versjon den var.
  Da hører det også med å kunne se at en installert skill har en nyere
  utgave, og at en redigert skill ikke skrives over av en oppdatering.
- **Delt objektlager for monorepoer.** `objects/info/alternates` mot et
  read-only mount av hostens `.git/objects`, med beskyttelse mot `git gc`
  på host (f.eks. en `keep`-ref i hostens repo per aktiv samtale).
- **GraalVM native-image for CLI-en.** Én kjørbar fil per plattform med
  oppstart under 50 ms. Krever refleksjons- og ressurskonfig for JSON og
  i18n; ingen endring i kjernen.
- **Signerte plugin-jar-er og profilpakker.** `gateway plugin add` og
  `profile set` verifiserer signatur (jarsigner eller sigstore) før kopiering.
- **Windows/WSL2.** `core.runtime.Platform` er stedet; stier og `keep-id`
  er de kjente problemene.
- **Docker som runtime.** `core.runtime` + `userns`-forskjeller.
- **Sentral profildistribusjon.** `profile update` fra en URL med signatur.
- **Revisjonslogg per samtale.** Squid-logg og gateway-logg filtrert på
  samtalens container-IP, lagret under `.sessions/<id>/audit/`.
- **Per-samtale nettverk.** Hvis inter-container-trafikk mellom samtaler
  må stoppes, kan hver samtale få eget nett som proxy og gateway kobles
  til dynamisk (`podman network connect`).

---

## 12. Migrering fra 1.1.2

`setup.sh migrate` (idempotent, med marker `state/.migrated-from-1.1.2`):

1. `sandbox.config`: `UID/GID/REPO/MEM_LIMIT/…` beholdes; legger til
   `SCHEMA_VERSION=2`, `PROFILE`, `DEFAULT_AGENT`, `LANG_UI=nb`.
2. Oppretter profilpakke `profiles/migrated/` fra den eksisterende
   installasjonen: `proxy/allowed-domains.txt` → `egress.allow`;
   `gateway/gateway.manifest.json` (schema 1) → schema 2 med én instans per
   gammel adapter (plugin-type lik gammel `id`, vertsnavn og alias fra
   oppføringen, `baseUrlEnv` og `secret.env` fra `read.secret`/`baseUrlEnv`);
   `gateway/allowlists/` og `gateway/secrets/*.env.example` med nye
   filnavn; verktøykjede
   `["node22","java17","java21","java25","kotlin","maven","gradle"]`;
   `ENTERPRISE_URL` → Copilot-adapterens konfig. Ingen verdier fra
   installasjonen skrives noe annet sted enn i denne profilpakken.
3. `copilot-config/conversations/*.json` → `state/conversations/` med
   `schemaVersion: 2`, `agent: "copilot-cli"`, `base` fra `baselineShas`
   (som `baseCommit`; `hostSha` ukjent → `null`), `deliveries` konvertert.
4. `.sessions/<id>/work/` beholdes; `out/` → `patches/`; `copilot-output-files/`
   → kopieres til hver eksisterende samtales `output/` (kan ikke tilordnes;
   dokumenteres). Migreringen trenger ingen Podman: `work/` og `output/` blir
   liggende på hosten til samtalen tas i bruk, og flyttes da inn i volumet (5.1).
5. `copilot-config/` (unntatt `conversations/`) → `state/agents/copilot-cli/`.
6. `~/.cache/cplt-sndbx/subnets.txt` → nytt register.
7. Sletter `docker-compose.yml` og `Containerfile`; kjører `rebuild-config`.
8. Rapporterer hva som må gjøres manuelt: bygge images, logge inn agenter.

GUI-et tilbyr migrering ved oppstart når `sandbox.config` mangler
`SCHEMA_VERSION`.

---

## 13. Implementasjonsrekkefølge og estimat

| Trinn | Innhold | Ukeverk |
|---|---|---|
| 1 | Kjerneoppsett: modulstruktur, `config`, `i18n`, `runtime`/`ProcessRunner`, låsing, `contract`-records med skjemagenerering, CLI-skjelett, `setup.sh`-launcher, `MockAdapter` | 2 |
| 2 | Kjøremiljø: basisimage, fragmenter, stakk, Squid, subnett, `render`, `doctor` | 2–3 |
| 3 | `session` (new/start/stop/remove/gc) med kopi/klon og per-samtale container | 1,5 |
| 4 | `ask`: `AgentAdapter`, `ProcessAdapter`, hendelsesstrøm, samtalelagring, Claude Code-adapter | 2 |
| 5 | OpenCode-adapter | 1–1,5 |
| 6 | `delivery` (review/apply) med alle bøtter og host-git-flyt | 2 |
| 7 | Gateway i Java: `gateway-api`, kjøretid på `jdk.httpserver`, `ServiceLoader`-plugins, `http`-plugin, portering av Confluence/Jira til jar-er, testharness, multi-stage image | 2,5–3 |
| 8 | GUI: viewmodel-lag mot kjernen, tilpassing av eksisterende visninger, nye faner, i18n, ArchUnit | 4–5 |
| 9 | `migrate` med generert profilpakke, verifisering av G2 og G6 | 1 |
| 10 | Pakking (jlink per plattform, jpackage, `upgrade` av `cli/`), CI, dokumentasjon (engelsk + norsk) | 3 |
| **Sum** | | **21–24** |

Omtrent 5–6 måneder for én senior utvikler, eller 3 måneder for to.
Anbefalt rekkefølge er som tabellen; trinn 4–5 og 8 kan gå parallelt
etter trinn 3, fordi GUI-et kan utvikles mot `MockAdapter` uten Podman.

---

## 14. Åpne punkter

1. **Eksakte CLI-flagg og hendelsesformat for Claude Code og OpenCode** må
   verifiseres mot versjonen som pinnes. Adapterkontrakten er laget for at
   dette kun berører `adapter.sh`.
2. **OpenCode: `run --format json` eller `serve`?** Avgjøres i trinn 5
   etter en kort utprøving av begge.
3. **Vedlegg utover bilder** (PDF, tekstfiler): adapteren erklærer
   støttede typer; GUI-et filtrerer. Ikke ferdig spesifisert per agent.
4. **Kostnadsvisning ved abonnement.** `costUsd` er `null` for
   abonnementsbruk; GUI-et viser tokens og kontekst, ikke pris.
5. **Navn.** «Lyngvi» er produktnavnet, skrevet `lyngvi` som
   identifikator; «CPLT-SNDBX» som vindustittel utgår.
6. **JSON-bibliotek.** Jackson er antatt; Gson eller jackson-jr er
   likeverdige. Velges i trinn 1 ut fra jlink-størrelse og
   record-støtte. Valget blir del av plugin-API-et (kap. 8.6 `json()`), så
   det låses før M8.
7. **Signering og notarisering** av jlink-bildet og GUI-appen på macOS
   (Gatekeeper). Nødvendig for distribusjon utenfor én organisasjon;
   krever et Apple Developer-sertifikat i CI.

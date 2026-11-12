# archiving-infrastructure

Repoet inneholder lokal testinfrastruktur og ende-til-ende-tester for innsending
og arkivering. Testene i `system-tests/` kontrollerer samspillet mellom
`innsending-api`, `soknadsmottaker` og `soknadsarkiverer`.
[arkiv-mock](https://github.com/navikt/arkiv-mock) simulerer Joark og SAF.
Shell-skriptene og `docker-compose.yml` bygger og starter komponentene,
mens `docs/` inneholder teamets systemguide.

Se [arkitekturoversikten med Mermaid](docs/innsending-arkitektur.md) og
[teamets systemguide på GitHub Pages](https://navikt.github.io/archiving-infrastructure/).

## Kjør testene lokalt

### Forutsetninger

- Installer [mise](https://mise.jdx.dev/) for Java- og Maven-versjonene i `mise.toml`.
- Ha Docker med Docker Compose tilgjengelig og Docker-miljøet startet.
- Klon `innsending-api`, `soknadsmottaker`, `soknadsarkiverer` og `arkiv-mock`
  som søskenmapper til dette repoet. Skriptene forventer stier som
  `../innsending-api`.

Kjør kommandoene fra roten av `archiving-infrastructure`, med mindre noe annet
er oppgitt.

> **Advarsel:** Byggskriptet forsøker å stoppe og fjerne alle containere i aktiv
> Docker-kontekst og slette ubrukte (dangling) Docker-volumer. Dette gjelder også
> andre prosjekter, og sletting av volumer kan fjerne lokale data. Bruk et eget
> Docker-miljø for testene hvis du har containere eller data du vil beholde.
> Dette gjelder `build.sh`, begge variantene av `run-end-to-end-tests.sh`
> og `start-components-in-docker.sh`, som alle kjører denne oppryddingen.

### Testcontainers (standard)

```sh
mise install
mise exec -- ./run-end-to-end-tests.sh
```

Skriptet bygger de fire komponentene og Docker-imagene deres før det kjører
testene. Testcontainers starter testmiljøet og stopper containerne etterpå.

Hvis imagene allerede er oppdaterte, kan du kjøre testene uten å bygge dem på nytt
fra `system-tests/`:

```sh
cd system-tests
mise exec -- mvn clean install
```

Etter kodeendringer i komponentene må du bygge imagene på nytt. Kjør hovedskriptet
fra reporoten, eller bruk `mise exec -- ./build.sh` for å bygge uten å kjøre testene.

### Docker Compose for feilsøking

```sh
mise exec -- ./run-end-to-end-tests.sh no-testcontainers
```

Denne varianten bygger og starter komponentene med Docker Compose og kjører
testene mot dem. Tester som krever at Testcontainers stopper og starter
komponenter, kjøres ikke i denne modusen. Containerne blir stående etter testene.
Stopp dem fra reporoten når du er ferdig:

```sh
docker compose down
```

For å starte miljøet uten å kjøre testene, bruk
`mise exec -- ./start-components-in-docker.sh`. Du kan deretter stoppe én komponent,
for eksempel med `docker compose stop soknadsarkiverer`, og kjøre den fra IDE-en
med konfigurasjon som peker mot Docker-miljøet.

Hvis et skript mangler kjørerettigheter, bruk `chmod +x` på det aktuelle skriptet.

## Tester i GitHub Actions

Pull requests i dette repoet kjører testene fra PR-branchen mot komponentenes
`main`-brancher. Komponentrepoene har også workflows som kjører disse testene mot
egne PR-er; de velger hvilken versjon av testrepoet som brukes.
Se [on-pr.yml](.github/workflows/on-pr.yml).

## Kontakt

Opprett en [issue](https://github.com/navikt/archiving-infrastructure/issues)
for spørsmål om repoet. Nav-ansatte kan kontakte teamet i Slack-kanalen
`#team-fyllut-sendinn`.

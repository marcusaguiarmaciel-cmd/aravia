# Builds locais e independência do GitHub Actions

Este guia documenta como economizar a cota do GitHub Actions hoje e como rodar os builds e deploys **localmente no seu computador** (sem depender de minutos pagos na nuvem).

## Como a cota funciona hoje (repositório privado)

- Plano Free do GitHub: **2.000 minutos/mês** para repositórios privados.
- Multiplicador por sistema:
  - **Linux (`Deploy Site`, `Build APK`):** 1x (1 minuto real = 1 minuto da cota).
  - **macOS (`Build IPA`):** **10x** (1 minuto real = 10 minutos da cota).
- **Configuração atual dos workflows:**
  - `Deploy Site (Hostinger)`: automático em pushes para `site/**` (~20 s = ~1 min da cota) e após `Build APK` / `Build IPA`.
  - `Build APK`: sob demanda (`Actions → Build APK → Run workflow` ou tag `v*` / `android-v*`). O emulador de screenshots só roda se marcar `run_screens = true` (~2–3 min sem emulador vs. ~8 min com emulador).
  - `Build IPA`: sob demanda (`Actions → Build IPA → Run workflow` ou tag `v*` / `ios-v*`).

---

## 1. Compilar o Android localmente (Windows, Linux ou Mac)

Custo: **R$ 0 e 0 minutos de Actions**.

### Pré-requisitos
- **JDK 17** (Temurin / Adoptium)
- **Android SDK** (via Android Studio ou *Command-line Tools*, API 35)
- Arquivo `.jks` da keystore de release (o mesmo usado em `ARAVIA_KEYSTORE_BASE64`, guardado fora do Git)

### Passo a passo (PowerShell no Windows ou Bash no Linux/Mac)

```sh
# 1. Variáveis de ambiente locais (nunca comite estes valores)
export ARAVIA_KEYSTORE="/caminho/para/aravia-release.jks"
export ARAVIA_KEYSTORE_PASSWORD="***"
export ARAVIA_KEY_ALIAS="***"
export ARAVIA_KEY_PASSWORD="***"
export STRAVA_CLIENT_ID="***"
export STRAVA_CLIENT_SECRET="***"
# Opcional (em produção):
export ADMOB_APP_ID="ca-app-pub-..."
export ADMOB_REWARDED_UNIT_ID="ca-app-pub-..."

# 2. Gerar APK assinado de release
cd android
./gradlew assembleRelease

# Saída pronta para subir no site como Aravia-Beta.apk:
# android/app/build/outputs/apk/release/app-release.apk
```

Para publicar na **Google Play** no futuro, troque apenas o comando por `./gradlew bundleRelease` (gera o `.aab` em `android/app/build/outputs/bundle/release/app-release.aab`).

---

## 2. Publicar o site na Hostinger sem GitHub Actions

Custo: **R$ 0 e 0 minutos de Actions**.

### Opção A — Enviar do seu PC via FTPS (FileZilla, WinSCP ou `lftp`)
1. Copie `app-release.apk` para `site/Aravia-Beta.apk` (e `Aravia.ipa` para `site/Aravia-Beta.ipa`, se houver).
2. Envie o conteúdo de `site/` para o diretório público da Hostinger (mantendo `site/api/runtime-config.php` e `site/api/storage/*.json` no servidor).

### Opção B — Git Deploy nativo da Hostinger (hPanel)
- No painel da Hostinger (`Avançado → Git`), conecte o repositório e aponte o deploy para a pasta `site/`. Cada push atualiza o site sem gastar um único minuto de GitHub Actions.

---

## 3. Usar seu próprio PC como runner do GitHub Actions (Self-Hosted Runner)

Custo: **R$ 0 e 0 minutos de Actions** — mantém toda a automação do GitHub, mas executando na sua máquina:

1. No GitHub: **Settings → Actions → Runners → New self-hosted runner**.
2. Instale no seu Windows/Linux (com JDK 17 + Android SDK já instalados).
3. Nos workflows de Linux (`build-apk.yml` e `deploy-site.yml`), basta trocar `runs-on: ubuntu-latest` por `runs-on: self-hosted`.

---

## 4. Compilar o iPhone (`.ipa`) fora do GitHub Actions

Como o compilador da Apple (Xcode) só roda em macOS, existem 3 caminhos:

1. **Codemagic (plano Free — recomendado se não tiver Mac):**
   - Oferece **500 minutos reais/mês grátis em Mac M2**, inclusive para repositórios privados (sem multiplicador 10x — equivale a ~200 builds de `.ipa` por mês).
   - Usa o mesmo script [`ios/tools/build-ipa.sh`](../ios/tools/build-ipa.sh).
2. **Mac próprio ou emprestado (futuro):**
   - Com Xcode + `brew install xcodegen`, basta rodar `cd ios && sh tools/build-ipa.sh` para gerar o `.ipa` localmente em ~1 minuto.
   - Para instalar direto no iPhone via cabo (sem Sideloadly): `cd ios && sh tools/device-install.sh`.
3. **Mac na nuvem sob demanda (ex.: MacStadium / Scaleway Apple Silicon):**
   - Pago por hora/dia — útil apenas quando for enviar para a App Store, caso não tenha um Mac físico.

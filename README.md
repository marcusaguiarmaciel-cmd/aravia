# Aravia

**`'ara`** *(Tupi: dia, tempo)* + **`via`** *(Latim: caminho)* — **o caminho do dia.**

Aplicativo brasileiro de saúde, sono, recuperação, treinos e rotas GPS para **Android** e **iPhone**, com sua companheira **Arara** que evolui conforme sua consistência real.

- **Site oficial e download Beta:** [aravia.app](https://aravia.app)
- **Contato:** [contato@aravia.app](mailto:contato@aravia.app)

> **Aviso de Licença (Todos os Direitos Reservados):** Este repositório é público **exclusivamente para compilação (CI/CD)** e leitura. **É proibida qualquer cópia, reutilização, modificação, redistribuição ou uso comercial/não comercial** do código, design, marca ou ilustrações do Aravia sem autorização prévia por escrito. Consulte [`LICENSE`](LICENSE).

---

## O que o Aravia faz

O app é organizado em **4 abas** (idênticas no Android e no iPhone), além do painel **Perfil e Ajustes** no avatar do canto superior direito:

- **Hoje:** pontuações diárias de **Sono**, **Recuperação** e **Esforço** (com explicação dos fatores e linha de base pessoal), leitura rápida do dia e insights automáticos.
- **Treinar:** sua **Arara** interativa (que evolui em 5 estágios com seus passos, treinos e rotas e pode ser compartilhada em um Ninho), treino ao vivo, **Rotas GPS** com replay 3D, zona automática de privacidade em casa, **Hub de Stories** (vídeo 1080×1920 e foto) e exportação GPX / Strava.
- **Evolução:** análise de **Tendências** (7, 30 e 90 dias) e detalhamento completo de **Sono** (estágios, hipnograma, regularidade e HRV).
- **Relógios:** compatibilidade universal via **Health Connect** (Android) e **Apple Saúde** (iPhone) para Apple Watch, Galaxy Watch, Garmin, Amazfit, Polar, Fitbit, Pixel Watch e Huawei, **cintas cardíacas Bluetooth** (`0x180D`) com medição Spot HRV, além de conexão Bluetooth direta para relógios/pulseiras **Xiaomi e Redmi** (como Redmi Watch 5 Lite, Redmi Watch 5 Active, Band 10 / 10 Pro e Watch S1 Active).

---

## Estrutura do repositório

| Pasta | Conteúdo |
|---|---|
| `ios/` | Aplicativo iOS em Swift / SwiftUI (iOS 17+) e suítes de testes em `ios/tests/` |
| `android/` | Aplicativo Android em Kotlin / Jetpack Compose (Android 10+) |
| `design/`, `scripts/` | Tokens de design e geradores compartilhados entre iOS e Android |
| `docs/` | Documentação técnica de protocolo BLE, formato de arquivos de atividade e build local ([`docs/BUILD-LOCAL.md`](docs/BUILD-LOCAL.md)) |

---

## Builds e CI/CD

Os workflows em `.github/workflows/` são disparados sob demanda (`workflow_dispatch`) ou por tag de release:

- **`Build IPA`** (`build-ipa.yml`): gera o `.ipa` de iOS e publica o artefato `Aravia-<N>`.
- **`Build APK`** (`build-apk.yml`): gera o `.apk` assinado de Android e publica o artefato `Aravia-Android-<N>`.
- **`Publish Builds (Hostinger)`** (`deploy-site.yml`): após um build concluído, publica automaticamente `Aravia-Beta.ipa` / `Aravia-Beta.apk` em [aravia.app](https://aravia.app).

Instruções de compilação local estão em [`docs/BUILD-LOCAL.md`](docs/BUILD-LOCAL.md).

---

## Licença e Avisos de Terceiros

- **Aravia (código, interface, marca, ilustrações e extensões):** **Todos os direitos reservados — Licença Proprietária.** Proibida qualquer reutilização, cópia, alteração ou redistribuição sem autorização expressa por escrito. Leia [`LICENSE`](LICENSE).
- **Avisos de terceiros:** trechos iniciais da camada de protocolo BLE iOS derivados do projeto [Freeband](https://github.com/cobanov/freeband) (MIT) mantêm seu aviso original em [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
- **Vetores de teste:** os arquivos `ios/tests/activity/fixtures/gb_*.json` são derivados da suíte de testes do [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0-or-later, detalhes em `ios/tests/activity/fixtures/README.md`).
- **Tipografia:** Barlow sob SIL Open Font License.

*Aravia é um projeto independente, feito à mão no Brasil, sem vínculo com fabricantes de relógios.*

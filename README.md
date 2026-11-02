# Tradutor HE→PT (offline)

Tradução simultânea de **hebraico falado → português do Brasil** no Android, sem dados móveis.
Otimizado para o **Samsung Galaxy A55**. Irmão do Tradutor ZH→PT (pode ter os dois instalados).

## Arquitetura
| Etapa | Modelo | Papel |
|---|---|---|
| Ouvir | Zipformer2 66M streaming hebraico (ivrit.ai / Yakov Kolani, sherpa-onnx int8) | texto em hebraico em tempo real |
| Legenda rápida | Google ML Kit (he→pt) | português aproximado (≈) em ~0,5 s |
| Tradução final | **Tencent HY-MT1.5-1.8B** (llama.cpp, Q4_K_M) | substitui a legenda rápida pela versão final |

## Fontes de áudio
- **Áudio interno do celular** (padrão): traduz o que está tocando em outro app — canal de notícias de Israel
  no navegador, YouTube, app da emissora. O Android pede autorização ("iniciar gravação/transmissão") ao tocar em Iniciar.
  Use a **tela dividida** ou a **janela pop-up** da Samsung: vídeo em cima, legendas embaixo.
  Obs.: alguns apps bloqueiam a captura (proteção de conteúdo); navegador e YouTube costumam funcionar.
- **Microfone** (traseiro, voz, bruto, comunicação): para TV na sala ou alguém falando.

## Compilar (GitHub Actions)
1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (incluindo `.github`).
2. **Actions → Compilar APK** roda sozinho. Se falhar, o passo "Erros de compilação" mostra o motivo.
3. Baixe o artefato **TradutorHE-PT-apk** e instale o `.apk`.

## Primeiro uso — uma vez, no Wi-Fi
1. Permita o microfone (exigido pelo Android também para o áudio interno) e as notificações.
2. **Baixar legenda rápida** (ML Kit, ~60 MB) e **Baixar tradutor HY-MT** (~1,1 GB). Ambos só baixam no Wi-Fi.
   Se você já baixou o HY-MT no app de mandarim, pode importar o mesmo `.gguf` com **Importar .gguf** (é o mesmo arquivo).
3. Depois disso, funciona em modo avião.

## Créditos dos modelos
- Reconhecimento: zipformer2-66m-streaming-he — Yakov Kolani / ivrit.ai, licença CC-BY-4.0.
- Tradução: Tencent HY-MT1.5-1.8B. Legenda rápida: Google ML Kit.

## Versões fixadas
sherpa-onnx 2.0.1 · llama.cpp b12059 · ML Kit Translate 17.0.3 · AGP 8.5.2 · Kotlin 1.9.24 · NDK 27.2

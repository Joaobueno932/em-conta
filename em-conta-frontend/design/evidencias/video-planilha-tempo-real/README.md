# Vídeo, planilha e atualização em tempo real (homologação com a Meta)

Capturas de 09/11/2026, com a API de homologação (número de teste da Meta) e o build de produção, em
1440 px e 390 px. Só balões de **arquivos sintéticos enviados pelo Em Conta**; nada do contato nem
dos arquivos recebidos do celular.

| Captura | O que mostra | Resultado na Meta |
|---|---|---|
| `mensagem-16-*` | MP4 convencional (H.264 + AAC) com legenda, player nativo e download | aceito → enviado → entregue → lido (dois tiques azuis); chegou e tocou no celular |
| `mensagem-17-*` | MP4 fragmentado com o mesmo conteúdo | falha confirmada pela Meta, #131053 "Media upload error" (exclamação vermelha; a causa abre no detalhe) |
| `mensagem-18-*` | Planilha XLSX: selo, nome, "Planilha XLSX · 5 KB" e download | entregue; abriu no celular |
| `mensagem-19-*` | PDF: selo, nome, visualizar e baixar | entregue; abriu no celular |

Medido no navegador durante os envios (aviso SSE e mudança do indicador, sem conteúdo):

- balão com relógio 12 a 60 ms depois de enviar; o registro do servidor substitui o balão local, e o
  texto fica uma única vez na conversa e na base;
- do processamento do status até o ícone mudar na tela: 44 a 140 ms;
- `sent` e `delivered` no mesmo segundo: "Enviada" por cerca de 50 ms e depois "Entregue", sem pausa
  forçada; `read` antes de `delivered`: "Lida" e nenhum retrocesso.

"Digitando…" foi confirmado no celular antes do texto, e a última mensagem recebida ficou com os tiques
azuis (exigência da Meta para o indicador).

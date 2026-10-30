import { createHash, createHmac, randomUUID } from 'node:crypto'
import { createServer, type Server } from 'node:http'
import process from 'node:process'

import type { APIRequestContext } from '@playwright/test'

/**
 * AMBIENTE EXTERNO CONTROLADO, SÓ PARA TESTE. Nada aqui fala com a Meta nem usa o número oficial:
 *
 * - `GraphControlada` é um servidor HTTP local que responde como a Graph API no envio
 *   (`POST /{versão}/{phone-number-id}/messages`), no upload de mídia (`POST .../media`), na consulta
 *   de mídia (`GET /{versão}/{media-id}`, com URL temporária) e no download dessa URL, com o formato
 *   documentado; a API de testes aponta para ele por WHATSAPP_GRAPH_URL.
 * - `webhookDeTeste` monta notificações no formato da Cloud API e as assina (HMAC-SHA256) com o
 *   segredo FICTÍCIO configurado na API de testes, como a Meta faria.
 *
 * Homologação com o canal real é outra etapa, com as credenciais do ambiente.
 */
export const SEGREDO_TESTE = process.env.E2E_WHATSAPP_APP_SECRET ?? ''
export const NUMERO_TESTE = process.env.E2E_WHATSAPP_PHONE_NUMBER_ID ?? ''
const PORTA = Number(process.env.E2E_GRAPH_PORT ?? 8099)

export interface EnvioRecebido {
  caminho: string
  para: string
  tipo: string
  texto: string
  legenda?: string
  idMidia?: string
  idExterno: string
}

/** Endereço da Graph controlada visto pela API de testes (dentro do Docker). */
const URL_PARA_API = process.env.E2E_GRAPH_URL_API ?? `http://host.docker.internal:${PORTA}`

export class GraphControlada {
  readonly envios: EnvioRecebido[] = []
  /** Uploads recebidos (`POST .../media`): tamanho e tipo declarado. */
  readonly uploads: { bytes: number; tipo: string }[] = []
  private readonly midias = new Map<string, { conteudo: Buffer; mime: string }>()
  private servidor: Server | null = null

  /** Registra uma mídia que "a Meta" passa a servir pelo id. Devolve o SHA-256 (hex) do arquivo. */
  midia(id: string, conteudo: Buffer, mime: string) {
    this.midias.set(id, { conteudo, mime })
    return createHash('sha256').update(conteudo).digest('hex')
  }

  async iniciar() {
    this.servidor = createServer((req, res) => {
      const partes: Buffer[] = []
      req.on('data', (parte: Buffer) => partes.push(parte))
      req.on('end', () => {
        const caminho = req.url?.split('?')[0] ?? ''
        const bruto = Buffer.concat(partes)
        if (caminho.startsWith('/arquivos/')) {
          const midia = this.midias.get(caminho.slice('/arquivos/'.length))
          if (!midia) return res.writeHead(404).end()
          res.writeHead(200, {
            'Content-Type': midia.mime,
            'Content-Length': midia.conteudo.length,
          })
          return res.end(midia.conteudo)
        }
        if (req.method === 'POST' && caminho.endsWith('/media')) {
          const tipo = /name="type"\r\n\r\n([^\r]+)/.exec(bruto.toString('latin1'))?.[1] ?? ''
          this.uploads.push({ bytes: bruto.length, tipo })
          res.writeHead(200, { 'Content-Type': 'application/json' })
          return res.end(JSON.stringify({ id: `midia-e2e-${randomUUID()}` }))
        }
        if (req.method === 'GET' && /^\/v[\d.]+\/[^/]+$/.test(caminho)) {
          const id = caminho.slice(caminho.lastIndexOf('/') + 1)
          const midia = this.midias.get(id)
          if (!midia) {
            res.writeHead(404, { 'Content-Type': 'application/json' })
            return res.end(JSON.stringify({ error: { message: 'Media not found', code: 100 } }))
          }
          res.writeHead(200, { 'Content-Type': 'application/json' })
          return res.end(
            JSON.stringify({
              messaging_product: 'whatsapp',
              url: `${URL_PARA_API}/arquivos/${id}`,
              mime_type: midia.mime,
              sha256: createHash('sha256').update(midia.conteudo).digest('hex'),
              file_size: midia.conteudo.length,
              id,
            }),
          )
        }
        const dados = JSON.parse(bruto.toString('utf8') || '{}') as {
          to?: string
          type?: string
          text?: { body?: string }
          image?: { id?: string; caption?: string }
          document?: { id?: string; caption?: string }
          audio?: { id?: string }
        }
        const idExterno = `wamid.E2E${randomUUID().replaceAll('-', '')}`
        const midia = dados.image ?? dados.document ?? dados.audio
        this.envios.push({
          caminho: req.url ?? '',
          para: dados.to ?? '',
          tipo: dados.type ?? '',
          texto: dados.text?.body ?? '',
          legenda: (dados.image ?? dados.document)?.caption,
          idMidia: midia?.id,
          idExterno,
        })
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({
            messaging_product: 'whatsapp',
            contacts: [{ input: dados.to, wa_id: dados.to }],
            messages: [{ id: idExterno, message_status: 'accepted' }],
          }),
        )
      })
    })
    await new Promise<void>((ok) => this.servidor!.listen(PORTA, ok))
  }

  async parar() {
    await new Promise<void>((ok) => (this.servidor ? this.servidor.close(() => ok()) : ok()))
  }
}

function notificacao(valor: Record<string, unknown>) {
  return {
    object: 'whatsapp_business_account',
    entry: [
      {
        id: 'e2e-conta-ficticia',
        changes: [
          {
            field: 'messages',
            value: {
              messaging_product: 'whatsapp',
              metadata: { display_phone_number: '000000000', phone_number_id: NUMERO_TESTE },
              ...valor,
            },
          },
        ],
      },
    ],
  }
}

async function postarAssinado(api: APIRequestContext, corpo: unknown) {
  const bruto = Buffer.from(JSON.stringify(corpo), 'utf8')
  const assinatura = createHmac('sha256', SEGREDO_TESTE).update(bruto).digest('hex')
  return api.post('/api/v1/integracoes/whatsapp/webhook', {
    data: bruto,
    headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': `sha256=${assinatura}` },
  })
}

export const webhookDeTeste = {
  /** Mensagem de mídia (`image`, `document` ou `audio`) com os metadados do webhook da Cloud API. */
  midia(
    api: APIRequestContext,
    waId: string,
    nome: string,
    tipo: 'image' | 'document' | 'audio',
    midia: { id: string; mime: string; sha256: string; nomeArquivo?: string; legenda?: string },
  ) {
    return postarAssinado(
      api,
      notificacao({
        contacts: [{ profile: { name: nome }, wa_id: waId }],
        messages: [
          {
            from: waId,
            id: `wamid.E2EIN${randomUUID().replaceAll('-', '')}`,
            timestamp: String(Math.floor(Date.now() / 1000)),
            type: tipo,
            [tipo]: {
              id: midia.id,
              mime_type: midia.mime,
              sha256: midia.sha256,
              ...(midia.nomeArquivo ? { filename: midia.nomeArquivo } : {}),
              ...(midia.legenda ? { caption: midia.legenda } : {}),
            },
          },
        ],
      }),
    )
  },
  mensagem(api: APIRequestContext, waId: string, nome: string, texto: string) {
    return postarAssinado(
      api,
      notificacao({
        contacts: [{ profile: { name: nome }, wa_id: waId }],
        messages: [
          {
            from: waId,
            id: `wamid.E2EIN${randomUUID().replaceAll('-', '')}`,
            timestamp: String(Math.floor(Date.now() / 1000)),
            type: 'text',
            text: { body: texto },
          },
        ],
      }),
    )
  },
  situacao(api: APIRequestContext, waId: string, idExterno: string, situacao: string) {
    return postarAssinado(
      api,
      notificacao({
        statuses: [
          {
            id: idExterno,
            status: situacao,
            timestamp: String(Math.floor(Date.now() / 1000)),
            recipient_id: waId,
          },
        ],
      }),
    )
  },
}

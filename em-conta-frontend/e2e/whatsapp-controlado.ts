import { createHmac, randomUUID } from 'node:crypto'
import { createServer, type Server } from 'node:http'
import process from 'node:process'

import type { APIRequestContext } from '@playwright/test'

/**
 * AMBIENTE EXTERNO CONTROLADO, SÓ PARA TESTE. Nada aqui fala com a Meta nem usa o número oficial:
 *
 * - `GraphControlada` é um servidor HTTP local que responde como a Graph API no envio
 *   (`POST /{versão}/{phone-number-id}/messages`), com o formato documentado; a API de testes aponta
 *   para ele por WHATSAPP_GRAPH_URL.
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
  texto: string
  idExterno: string
}

export class GraphControlada {
  readonly envios: EnvioRecebido[] = []
  private servidor: Server | null = null

  async iniciar() {
    this.servidor = createServer((req, res) => {
      let corpo = ''
      req.on('data', (parte: Buffer) => (corpo += parte.toString('utf8')))
      req.on('end', () => {
        const dados = JSON.parse(corpo || '{}') as { to?: string; text?: { body?: string } }
        const idExterno = `wamid.E2E${randomUUID().replaceAll('-', '')}`
        this.envios.push({
          caminho: req.url ?? '',
          para: dados.to ?? '',
          texto: dados.text?.body ?? '',
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

import { fireEvent, render, screen } from '@testing-library/vue'
import { describe, expect, it } from 'vitest'

import type { Item } from '../atendimento-api'
import IndicadorSituacao from '../IndicadorSituacao.vue'
import { maisNovo } from '../linha-do-tempo-estado'

const ACEITA = 'Aceita pela Meta, aguardando confirmação de envio'

function indicador(situacao: string, erroTitulo?: string) {
  return render(IndicadorSituacao, { props: { situacao, erroTitulo } })
}

const tracos = (el: Element) => el.querySelectorAll('path').length

describe('indicador da situação de uma mensagem enviada', () => {
  it.each([
    ['PENDENTE', 'Aguardando envio'],
    ['ENVIANDO', 'Enviando'],
    ['ACEITA', ACEITA],
    ['ENVIADA', 'Enviada'],
    ['ENTREGUE', 'Entregue'],
    ['LIDA', 'Lida'],
  ])('%s tem ícone com rótulo acessível e tooltip', (situacao, rotulo) => {
    indicador(situacao)
    const icone = screen.getByRole('img', { name: rotulo })
    expect(icone.getAttribute('title')).toBe(rotulo)
  })

  it('aceita pela Meta continua como processamento (relógio), não como enviada', () => {
    const { unmount } = indicador('ENVIANDO')
    const relogio = screen.getByRole('img', { name: 'Enviando' }).innerHTML
    unmount()
    indicador('ACEITA')
    const aceita = screen.getByRole('img', { name: ACEITA })
    expect(aceita.innerHTML).toBe(relogio)
    expect(screen.queryByRole('img', { name: 'Enviada' })).toBeNull()
  })

  it('um tique para enviada, dois para entregue e dois azuis para lida', () => {
    const enviada = indicador('ENVIADA')
    const tiqueSimples = screen.getByRole('img', { name: 'Enviada' })
    expect(tracos(tiqueSimples)).toBe(1)
    enviada.unmount()

    const entregue = indicador('ENTREGUE')
    const duplo = screen.getByRole('img', { name: 'Entregue' })
    expect(tracos(duplo)).toBe(2)
    expect(duplo.classList.contains('lida')).toBe(false)
    entregue.unmount()

    indicador('LIDA')
    const lida = screen.getByRole('img', { name: 'Lida' })
    expect(tracos(lida)).toBe(2)
    expect(lida.classList.contains('lida')).toBe(true)
  })

  it('falha confirmada: exclamação vermelha e causa num detalhe que abre e fecha', async () => {
    indicador('FALHA', '(#131030) Recipient phone number not in allowed list')
    const botao = screen.getByRole('button', { name: /Falha no envio/ })
    expect(botao.classList.contains('falha')).toBe(true)
    expect(botao.getAttribute('aria-expanded')).toBe('false')
    const causa = 'Falha no envio: (#131030) Recipient phone number not in allowed list'
    expect(botao.getAttribute('title')).toBe(causa)
    expect(screen.getByText(causa).style.display).toBe('none')

    await fireEvent.click(botao)
    expect(botao.getAttribute('aria-expanded')).toBe('true')
    const detalhe = screen.getByText(causa)
    expect(detalhe.style.display).toBe('')
    expect(botao.getAttribute('aria-controls')).toBe(detalhe.id)
    expect(detalhe.textContent).not.toContain(' :')

    await fireEvent.keyDown(botao, { key: 'Escape' })
    expect(botao.getAttribute('aria-expanded')).toBe('false')
  })

  it('janela expirada também é falha confirmada, com o motivo no detalhe', async () => {
    indicador('JANELA_EXPIRADA')
    const botao = screen.getByRole('button', { name: /Não enviada/ })
    expect(botao.classList.contains('falha')).toBe(true)
    await fireEvent.click(botao)
    expect(screen.getByText(/janela de 24 horas expirou antes do envio/)).toBeTruthy()
  })

  it('resultado desconhecido aparece escrito e com indicador próprio, diferente da falha', async () => {
    indicador('DESCONHECIDA')
    const botao = screen.getByRole('button', { name: /Envio não confirmado/ })
    expect(screen.getByText('Envio não confirmado')).toBeTruthy()
    expect(botao.classList.contains('incerto')).toBe(true)
    expect(botao.classList.contains('falha')).toBe(false)
    await fireEvent.click(botao)
    expect(screen.getByText(/confirme com o cliente antes de reenviar/)).toBeTruthy()
  })

  it('situação desconhecida pelo frontend ou ausente não mostra indicador', () => {
    const { container } = render(IndicadorSituacao, { props: { situacao: null } })
    expect(container.textContent).toBe('')
  })

  it('o ícone acompanha a situação atual quando ela muda', async () => {
    const { rerender } = indicador('ACEITA')
    expect(screen.getByRole('img', { name: ACEITA })).toBeTruthy()
    await rerender({ situacao: 'ENTREGUE' })
    expect(screen.getByRole('img', { name: 'Entregue' })).toBeTruthy()
    await rerender({ situacao: 'LIDA' })
    expect(screen.getByRole('img', { name: 'Lida' })).toBeTruthy()
    expect(screen.queryByRole('img', { name: ACEITA })).toBeNull()
  })

  it('status fora de ordem (sent depois de read) não faz o indicador regredir', () => {
    const base = { tipo: 'MENSAGEM', id: 5, direcao: 'SAIDA', cursor: 'c5' } as unknown as Item
    const lida = { ...base, situacao: 'LIDA', atualizadoEm: '2026-11-09T15:28:00Z' } as Item
    const enviadaAtrasada = {
      ...base,
      situacao: 'ENVIADA',
      atualizadoEm: '2026-11-09T15:27:13Z',
    } as Item

    const atual = maisNovo(maisNovo(undefined, lida), enviadaAtrasada)
    indicador(atual.situacao ?? '')
    expect(screen.getByRole('img', { name: 'Lida' })).toBeTruthy()
    expect(screen.queryByRole('img', { name: 'Enviada' })).toBeNull()
  })
})

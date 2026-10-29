import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'

import AssociadoPage from '@/features/associados/AssociadoPage.vue'
import AssociadosPage from '@/features/associados/AssociadosPage.vue'
import EditarAssociadoPage from '@/features/associados/EditarAssociadoPage.vue'
import NovoAssociadoPage from '@/features/associados/NovoAssociadoPage.vue'
import UcFormPage from '@/features/associados/UcFormPage.vue'
import AcessoNegadoPage from '@/features/auth/AcessoNegadoPage.vue'
import FazendaFormPage from '@/features/usinas/FazendaFormPage.vue'
import FazendasPage from '@/features/usinas/FazendasPage.vue'
import UsinaFormPage from '@/features/usinas/UsinaFormPage.vue'
import UsinaPage from '@/features/usinas/UsinaPage.vue'
import UsinasPage from '@/features/usinas/UsinasPage.vue'
import AlterarSenhaPage from '@/features/auth/AlterarSenhaPage.vue'
import DefinicaoSenhaPage from '@/features/auth/DefinicaoSenhaPage.vue'
import LoginPage from '@/features/auth/LoginPage.vue'
import RecuperarSenhaPage from '@/features/auth/RecuperarSenhaPage.vue'
import InicioPage from '@/features/inicio/InicioPage.vue'
import SystemStatusPage from '@/features/system-status/SystemStatusPage.vue'
import UsuarioPage from '@/features/usuarios/UsuarioPage.vue'
import UsuariosPage from '@/features/usuarios/UsuariosPage.vue'

import AreaAutenticadaLayout from './AreaAutenticadaLayout.vue'

/** Rotas sem `meta.acesso` exigem sessão (ver guards.ts). */
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AreaAutenticadaLayout,
    children: [
      { path: '', name: 'inicio', component: InicioPage },
      { path: 'conta/senha', name: 'alterar-senha', component: AlterarSenhaPage },
      {
        path: 'admin/usuarios',
        name: 'usuarios',
        component: UsuariosPage,
        meta: { permissao: 'usuarios:consultar' },
      },
      {
        path: 'admin/usuarios/:id(\\d+)',
        name: 'usuario',
        component: UsuarioPage,
        props: true,
        meta: { permissao: 'usuarios:consultar' },
      },
      {
        path: 'associados',
        name: 'associados',
        component: AssociadosPage,
        meta: { permissao: 'associados:consultar' },
      },
      {
        path: 'associados/novo',
        name: 'associado-novo',
        component: NovoAssociadoPage,
        meta: { permissao: 'associados:cadastrar' },
      },
      {
        path: 'associados/:id(\\d+)',
        name: 'associado',
        component: AssociadoPage,
        props: true,
        meta: { permissao: 'associados:consultar' },
      },
      {
        path: 'associados/:id(\\d+)/editar',
        name: 'associado-editar',
        component: EditarAssociadoPage,
        props: true,
        meta: { permissao: 'associados:editar' },
      },
      {
        path: 'associados/:id(\\d+)/ucs/nova',
        name: 'uc-nova',
        component: UcFormPage,
        props: true,
        meta: { permissao: 'ucs:cadastrar' },
      },
      {
        path: 'associados/:id(\\d+)/ucs/:ucId(\\d+)/editar',
        name: 'uc-editar',
        component: UcFormPage,
        props: true,
        meta: { permissao: 'ucs:editar' },
      },
      {
        path: 'usinas',
        name: 'usinas',
        component: UsinasPage,
        meta: { permissao: 'usinas:consultar' },
      },
      {
        path: 'usinas/nova',
        name: 'usina-nova',
        component: UsinaFormPage,
        meta: { permissao: 'usinas:cadastrar' },
      },
      {
        path: 'usinas/:id(\\d+)',
        name: 'usina',
        component: UsinaPage,
        props: true,
        meta: { permissao: 'usinas:consultar' },
      },
      {
        path: 'usinas/:id(\\d+)/editar',
        name: 'usina-editar',
        component: UsinaFormPage,
        props: true,
        meta: { permissao: 'usinas:editar' },
      },
      {
        path: 'fazendas',
        name: 'fazendas',
        component: FazendasPage,
        meta: { permissao: 'usinas:consultar' },
      },
      {
        path: 'fazendas/nova',
        name: 'fazenda-nova',
        component: FazendaFormPage,
        meta: { permissao: 'usinas:cadastrar' },
      },
      {
        path: 'fazendas/:id(\\d+)/editar',
        name: 'fazenda-editar',
        component: FazendaFormPage,
        props: true,
        meta: { permissao: 'usinas:editar' },
      },
      { path: 'acesso-negado', name: 'acesso-negado', component: AcessoNegadoPage },
    ],
  },
  { path: '/entrar', name: 'entrar', component: LoginPage, meta: { acesso: 'visitante' } },
  {
    path: '/recuperar-senha',
    name: 'recuperar-senha',
    component: RecuperarSenhaPage,
    meta: { acesso: 'visitante' },
  },
  {
    path: '/redefinir-senha',
    name: 'redefinir-senha',
    component: DefinicaoSenhaPage,
    props: { finalidade: 'REDEFINICAO_SENHA' },
    meta: { acesso: 'publico' },
  },
  {
    path: '/convite',
    name: 'convite',
    component: DefinicaoSenhaPage,
    props: { finalidade: 'CONVITE' },
    meta: { acesso: 'publico' },
  },
  {
    path: '/sistema/status',
    name: 'system-status',
    component: SystemStatusPage,
    meta: { acesso: 'publico' },
  },
  { path: '/:pathMatch(.*)*', name: 'nao-encontrada', redirect: '/' },
]

export function createAppRouter(
  history: RouterHistory = createWebHistory(import.meta.env.BASE_URL),
) {
  return createRouter({ history, routes })
}

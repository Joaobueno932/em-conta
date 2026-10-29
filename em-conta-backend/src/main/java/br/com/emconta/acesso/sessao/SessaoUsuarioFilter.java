package br.com.emconta.acesso.sessao;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Revalida a sessão a cada requisição: carrega situação, versão de segurança e permissões atuais do
 * banco. Sessão revogada é invalidada e a requisição segue como anônima (resultando em 401 com
 * {@code SESSAO_ENCERRADA} se a rota exigir autenticação). As autoridades atualizadas valem apenas
 * para a requisição; nada é regravado na sessão.
 */
public class SessaoUsuarioFilter extends OncePerRequestFilter {

    public static final String ATRIBUTO_SESSAO_ENCERRADA = SessaoUsuarioFilter.class.getName() + ".ENCERRADA";

    private final SessoesUsuario sessoes;

    public SessaoUsuarioFilter(SessoesUsuario sessoes) {
        this.sessoes = sessoes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication atual = SecurityContextHolder.getContext().getAuthentication();
        if (atual != null && atual.getPrincipal() instanceof SessaoUsuario sessao) {
            Optional<SessoesUsuario.SessaoValida> valida = sessoes.validar(sessao);
            if (valida.isPresent()) {
                SecurityContext contexto = SecurityContextHolder.createEmptyContext();
                contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        valida.get().principal(), null, valida.get().autoridades()));
                SecurityContextHolder.setContext(contexto);
                if (valida.get().promovida()) {
                    // Esta sessão fez a troca de senha: grava o principal na nova versão. Sem
                    // autoridades, como no login; elas são recalculadas a cada requisição.
                    HttpSession httpSession = request.getSession(false);
                    if (httpSession != null) {
                        SecurityContext gravado = SecurityContextHolder.createEmptyContext();
                        gravado.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                valida.get().principal(), null, List.of()));
                        httpSession.setAttribute(
                                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, gravado);
                    }
                }
            } else {
                SecurityContextHolder.clearContext();
                HttpSession httpSession = request.getSession(false);
                if (httpSession != null) {
                    httpSession.invalidate();
                }
                request.setAttribute(ATRIBUTO_SESSAO_ENCERRADA, Boolean.TRUE);
            }
        }
        chain.doFilter(request, response);
    }
}

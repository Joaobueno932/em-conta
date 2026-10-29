package br.com.emconta.acesso.web;

import br.com.emconta.acesso.sessao.SessaoUsuario;
import br.com.emconta.acesso.usuarios.Ator;
import br.com.emconta.acesso.usuarios.Permissao;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

final class Requisicoes {

    private Requisicoes() {}

    /**
     * IP do cliente. Com {@code server.forward-headers-strategy=native}, o Tomcat só aceita
     * {@code X-Forwarded-For} vindo de proxies confiáveis ({@code server.tomcat.remoteip.internal-proxies}).
     */
    static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    static SessaoUsuario sessao(Authentication autenticacao) {
        return (SessaoUsuario) autenticacao.getPrincipal();
    }

    /** Ator com as permissões que o servidor calculou nesta requisição (nunca vindas do cliente). */
    static Ator ator(Authentication autenticacao) {
        Set<String> autoridades = autenticacao.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        Set<Permissao> permissoes = EnumSet.noneOf(Permissao.class);
        Arrays.stream(Permissao.values())
                .filter(p -> autoridades.contains(p.autoridade()))
                .forEach(permissoes::add);
        return new Ator(sessao(autenticacao).usuarioId(), permissoes);
    }
}

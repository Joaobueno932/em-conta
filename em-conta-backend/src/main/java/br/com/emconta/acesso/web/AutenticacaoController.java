package br.com.emconta.acesso.web;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.sessao.Autenticacao;
import br.com.emconta.acesso.sessao.SessaoUsuario;
import br.com.emconta.acesso.sessao.SessoesUsuario;
import br.com.emconta.acesso.usuarios.Credenciais;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import br.com.emconta.acesso.web.AcessoApi.AceiteConviteRequest;
import br.com.emconta.acesso.web.AcessoApi.AlteracaoSenhaRequest;
import br.com.emconta.acesso.web.AcessoApi.CsrfResponse;
import br.com.emconta.acesso.web.AcessoApi.LoginRequest;
import br.com.emconta.acesso.web.AcessoApi.MensagemResponse;
import br.com.emconta.acesso.web.AcessoApi.RecuperacaoSenhaRequest;
import br.com.emconta.acesso.web.AcessoApi.RedefinicaoSenhaRequest;
import br.com.emconta.acesso.web.AcessoApi.TokenValidoResponse;
import br.com.emconta.acesso.web.AcessoApi.UsuarioAtualResponse;
import br.com.emconta.acesso.web.AcessoApi.ValidacaoTokenRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Autenticação", description = "Sessão, CSRF e credenciais do usuário")
class AutenticacaoController {

    private final Autenticacao autenticacao;
    private final Credenciais credenciais;
    private final UsuarioRepository usuarios;
    private final Auditoria auditoria;
    private final SessoesUsuario sessoes;
    private final CookieCsrfTokenRepository csrfTokens;
    private final String cookieSessao;
    private final SecurityContextRepository contextos = new HttpSessionSecurityContextRepository();
    private final SessionAuthenticationStrategy estrategiaLogin;

    AutenticacaoController(
            Autenticacao autenticacao,
            Credenciais credenciais,
            UsuarioRepository usuarios,
            Auditoria auditoria,
            SessoesUsuario sessoes,
            CookieCsrfTokenRepository csrfTokens,
            @Value("${server.servlet.session.cookie.name:EMCONTA_SESSION}") String cookieSessao) {
        this.autenticacao = autenticacao;
        this.credenciais = credenciais;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.sessoes = sessoes;
        this.csrfTokens = csrfTokens;
        this.cookieSessao = cookieSessao;
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(csrfTokens);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        // Novo id de sessão (contra fixação) e novo token CSRF a cada login.
        this.estrategiaLogin =
                new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    @GetMapping("/csrf")
    @Operation(
            operationId = "obterCsrf",
            summary = "Emite o cookie XSRF-TOKEN; o valor deve voltar no cabeçalho indicado em requisições mutáveis")
    CsrfResponse csrf(@Parameter(hidden = true) CsrfToken token) {
        token.getToken(); // força a gravação do cookie
        return new CsrfResponse(token.getHeaderName());
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "entrar", summary = "Autentica e cria a sessão (cookie HttpOnly)")
    @ApiResponse(responseCode = "200", description = "Sessão criada")
    @ApiResponse(responseCode = "401", description = "CREDENCIAIS_INVALIDAS (resposta genérica)")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS, com Retry-After")
    UsuarioAtualResponse login(
            @Valid @RequestBody LoginRequest corpo, HttpServletRequest request, HttpServletResponse response) {
        SessaoUsuario sessao = autenticacao.autenticar(corpo.email(), corpo.senha(), Requisicoes.ip(request));
        Authentication autenticado = UsernamePasswordAuthenticationToken.authenticated(sessao, null, List.of());
        estrategiaLogin.onAuthentication(autenticado, request, response);
        salvarNaSessao(autenticado, request, response);
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken novoToken) {
            novoToken.getToken(); // grava o novo XSRF-TOKEN já nesta resposta
        }
        return usuarioAtual(sessao.usuarioId(), autoridadesVigentes(sessao));
    }

    @PostMapping("/logout")
    @Operation(operationId = "sair", summary = "Encerra a sessão e limpa os cookies de sessão e CSRF")
    @ApiResponse(responseCode = "204", description = "Sessão encerrada (também quando não havia sessão)")
    ResponseEntity<Void> logout(Authentication autenticacao, HttpServletRequest request, HttpServletResponse response) {
        if (autenticacao != null && autenticacao.getPrincipal() instanceof SessaoUsuario sessao) {
            auditoria.registrar(
                    TipoEvento.LOGOUT, sessao.usuarioId(), sessao.usuarioId(), Requisicoes.ip(request), null);
        }
        new SecurityContextLogoutHandler().logout(request, response, autenticacao);
        new CsrfLogoutHandler(csrfTokens).logout(request, response, autenticacao);
        new CookieClearingLogoutHandler(cookieSessao).logout(request, response, autenticacao);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessao")
    @Operation(operationId = "obterSessao", summary = "Usuário autenticado e permissões vigentes")
    @ApiResponse(responseCode = "200", description = "Sessão válida")
    @ApiResponse(responseCode = "401", description = "NAO_AUTENTICADO, SESSAO_EXPIRADA ou SESSAO_ENCERRADA")
    UsuarioAtualResponse sessao(Authentication autenticacao) {
        return usuarioAtual(Requisicoes.sessao(autenticacao).usuarioId(), autenticacao.getAuthorities());
    }

    @PostMapping(path = "/senha/alteracao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "alterarSenha",
            summary = "Troca a senha confirmando a atual; mantém esta sessão e encerra as demais")
    @ApiResponse(responseCode = "204", description = "Senha alterada")
    @ApiResponse(
            responseCode = "409",
            description = "CREDENCIAL_ALTERADA: senha ou sessão mudou durante a operação; nada foi alterado")
    ResponseEntity<Void> alterarSenha(
            @Valid @RequestBody AlteracaoSenhaRequest corpo,
            Authentication autenticacao,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession sessaoHttp = request.getSession(false);
        if (sessaoHttp == null) {
            throw new FalhaAcesso(HttpStatus.UNAUTHORIZED, "NAO_AUTENTICADO", "Autenticação necessária.");
        }
        SessaoUsuario atualizada = credenciais.alterarSenha(
                Requisicoes.sessao(autenticacao),
                corpo.senhaAtual(),
                corpo.novaSenha(),
                sessaoHttp.getId(),
                Requisicoes.ip(request));
        request.changeSessionId();
        salvarNaSessao(
                UsernamePasswordAuthenticationToken.authenticated(atualizada, null, List.of()), request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/senha/recuperacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "solicitarRecuperacaoSenha", summary = "Solicita link de redefinição (resposta genérica)")
    @ApiResponse(responseCode = "202", description = "Sempre a mesma resposta, exista ou não a conta")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS a partir deste IP")
    ResponseEntity<MensagemResponse> solicitarRecuperacao(
            @Valid @RequestBody RecuperacaoSenhaRequest corpo, HttpServletRequest request) {
        credenciais.solicitarRecuperacao(corpo.email(), Requisicoes.ip(request));
        return ResponseEntity.accepted()
                .body(new MensagemResponse(
                        "Se houver uma conta ativa com este e-mail, enviaremos as instruções em instantes."));
    }

    @PostMapping(path = "/senha/redefinicao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "redefinirSenha", summary = "Define nova senha pelo link; encerra todas as sessões")
    @ApiResponse(responseCode = "204", description = "Senha redefinida")
    @ApiResponse(responseCode = "400", description = "TOKEN_INVALIDO ou SENHA_FORA_DA_POLITICA")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS a partir deste IP, com Retry-After")
    ResponseEntity<Void> redefinirSenha(@Valid @RequestBody RedefinicaoSenhaRequest corpo, HttpServletRequest request) {
        credenciais.redefinirSenha(corpo.token(), corpo.novaSenha(), Requisicoes.ip(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/tokens/validacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "validarToken", summary = "Confere um link de convite ou redefinição sem consumi-lo")
    @ApiResponse(responseCode = "200", description = "Link válido")
    @ApiResponse(responseCode = "400", description = "TOKEN_INVALIDO")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS a partir deste IP, com Retry-After")
    TokenValidoResponse validarToken(@Valid @RequestBody ValidacaoTokenRequest corpo, HttpServletRequest request) {
        Credenciais.TokenValido valido =
                credenciais.validarToken(corpo.token(), corpo.finalidade(), Requisicoes.ip(request));
        return new TokenValidoResponse(valido.finalidade(), valido.nome());
    }

    @PostMapping(path = "/convites/aceitacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "aceitarConvite", summary = "Define a senha pelo convite e ativa a conta")
    @ApiResponse(responseCode = "204", description = "Conta ativada; o usuário pode entrar")
    @ApiResponse(responseCode = "400", description = "TOKEN_INVALIDO ou SENHA_FORA_DA_POLITICA")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS a partir deste IP, com Retry-After")
    ResponseEntity<Void> aceitarConvite(@Valid @RequestBody AceiteConviteRequest corpo, HttpServletRequest request) {
        credenciais.aceitarConvite(corpo.token(), corpo.senha(), Requisicoes.ip(request));
        return ResponseEntity.noContent().build();
    }

    private void salvarNaSessao(Authentication autenticado, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(autenticado);
        SecurityContextHolder.setContext(contexto);
        contextos.saveContext(contexto, request, response);
    }

    private List<GrantedAuthority> autoridadesVigentes(SessaoUsuario sessao) {
        return sessoes.autoridadesVigentes(sessao).orElse(List.of());
    }

    private UsuarioAtualResponse usuarioAtual(long id, Collection<? extends GrantedAuthority> autoridades) {
        Usuario usuario = usuarios.findById(id).orElseThrow(FalhaAcesso::usuarioNaoEncontrado);
        return new UsuarioAtualResponse(
                usuario.getId(),
                usuario.getNome(),
                usuario.getEmail(),
                List.copyOf(usuario.getPerfis()),
                autoridades.stream()
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .toList());
    }
}

package br.com.emconta.acesso.web;

import br.com.emconta.acesso.usuarios.GestaoUsuarios;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.web.AcessoApi.ConviteCriadoResponse;
import br.com.emconta.acesso.web.AcessoApi.ConviteRequest;
import br.com.emconta.acesso.web.AcessoApi.EdicaoUsuarioRequest;
import br.com.emconta.acesso.web.AcessoApi.PaginaUsuariosResponse;
import br.com.emconta.acesso.web.AcessoApi.PerfilResponse;
import br.com.emconta.acesso.web.AcessoApi.PerfisRequest;
import br.com.emconta.acesso.web.AcessoApi.ReenvioConviteResponse;
import br.com.emconta.acesso.web.AcessoApi.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administração de usuários. As permissões de cada rota são exigidas na SecurityConfiguration. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Usuários", description = "Administração de usuários internos")
class UsuariosController {

    private final GestaoUsuarios gestao;

    UsuariosController(GestaoUsuarios gestao) {
        this.gestao = gestao;
    }

    @GetMapping("/usuarios")
    @Operation(operationId = "listarUsuarios", summary = "Lista paginada, ordenada por nome (usuarios:consultar)")
    PaginaUsuariosResponse listar(
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho) {
        return PaginaUsuariosResponse.de(gestao.listar(pagina, tamanho));
    }

    @GetMapping("/usuarios/{id}")
    @Operation(operationId = "obterUsuario", summary = "Dados de um usuário (usuarios:consultar)")
    UsuarioResponse obter(@PathVariable long id) {
        return UsuarioResponse.de(gestao.obter(id));
    }

    @PostMapping(path = "/usuarios", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "convidarUsuario",
            summary = "Cria usuário convidado e envia o convite (usuarios:convidar)")
    @ApiResponse(responseCode = "201", description = "Usuário convidado; conviteEnviado indica se o e-mail foi enviado")
    @ApiResponse(responseCode = "409", description = "EMAIL_EM_USO, CONVITE_PENDENTE ou USUARIO_DESATIVADO")
    ResponseEntity<ConviteCriadoResponse> convidar(
            @Valid @RequestBody ConviteRequest corpo, Authentication autenticacao, HttpServletRequest request) {
        GestaoUsuarios.ResultadoConvite resultado = gestao.convidar(
                Requisicoes.ator(autenticacao), corpo.nome(), corpo.email(), corpo.perfis(), Requisicoes.ip(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ConviteCriadoResponse(UsuarioResponse.de(resultado.usuario()), resultado.conviteEnviado()));
    }

    @PostMapping("/usuarios/{id}/convite")
    @Operation(
            operationId = "reenviarConvite",
            summary = "Reenvia o convite, invalidando o link anterior (usuarios:convidar)")
    @ApiResponse(responseCode = "202", description = "Novo link emitido; conviteEnviado indica se o e-mail foi enviado")
    @ApiResponse(responseCode = "429", description = "MUITAS_TENTATIVAS")
    ResponseEntity<ReenvioConviteResponse> reenviarConvite(
            @PathVariable long id, Authentication autenticacao, HttpServletRequest request) {
        boolean enviado = gestao.reenviarConvite(Requisicoes.ator(autenticacao), id, Requisicoes.ip(request));
        return ResponseEntity.accepted().body(new ReenvioConviteResponse(enviado));
    }

    @PatchMapping(path = "/usuarios/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editarUsuario", summary = "Edita o nome (usuarios:editar)")
    UsuarioResponse editar(
            @PathVariable long id,
            @Valid @RequestBody EdicaoUsuarioRequest corpo,
            Authentication autenticacao,
            HttpServletRequest request) {
        return UsuarioResponse.de(
                gestao.renomear(Requisicoes.ator(autenticacao), id, corpo.nome(), Requisicoes.ip(request)));
    }

    @PutMapping(path = "/usuarios/{id}/perfis", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "definirPerfis", summary = "Substitui os perfis (usuarios:atribuir-perfis)")
    UsuarioResponse definirPerfis(
            @PathVariable long id,
            @Valid @RequestBody PerfisRequest corpo,
            Authentication autenticacao,
            HttpServletRequest request) {
        return UsuarioResponse.de(
                gestao.definirPerfis(Requisicoes.ator(autenticacao), id, corpo.perfis(), Requisicoes.ip(request)));
    }

    @PostMapping("/usuarios/{id}/desativacao")
    @Operation(
            operationId = "desativarUsuario",
            summary = "Desativa e encerra as sessões do usuário (usuarios:alterar-situacao)")
    UsuarioResponse desativar(@PathVariable long id, Authentication autenticacao, HttpServletRequest request) {
        return UsuarioResponse.de(gestao.desativar(Requisicoes.ator(autenticacao), id, Requisicoes.ip(request)));
    }

    @PostMapping("/usuarios/{id}/reativacao")
    @Operation(
            operationId = "reativarUsuario",
            summary = "Reativa: volta a ATIVO se já tinha senha, senão a CONVIDADO (usuarios:alterar-situacao)")
    UsuarioResponse reativar(@PathVariable long id, Authentication autenticacao, HttpServletRequest request) {
        return UsuarioResponse.de(gestao.reativar(Requisicoes.ator(autenticacao), id, Requisicoes.ip(request)));
    }

    @GetMapping("/perfis")
    @Operation(operationId = "listarPerfis", summary = "Perfis atribuíveis e suas permissões (usuarios:consultar)")
    List<PerfilResponse> perfis() {
        return Arrays.stream(Perfil.values()).map(PerfilResponse::de).toList();
    }
}

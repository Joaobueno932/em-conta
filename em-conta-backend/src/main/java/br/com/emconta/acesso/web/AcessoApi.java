package br.com.emconta.acesso.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.acesso.tokens.FinalidadeToken;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import br.com.emconta.acesso.usuarios.Usuario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;

/**
 * DTOs HTTP do módulo de acesso. Nunca incluem hash de senha, tokens, versão de segurança ou outros
 * dados internos. Os limites de tamanho aqui só barram entradas abusivas; as regras de negócio
 * (formato do e-mail, política de senha) são aplicadas nos serviços.
 */
final class AcessoApi {

    private AcessoApi() {}

    @Schema(name = "LoginRequest")
    record LoginRequest(
            @Schema(requiredMode = REQUIRED, example = "maria@exemplo.com.br") @NotNull @Size(max = 320)
            String email,

            @Schema(requiredMode = REQUIRED, format = "password") @NotNull @Size(max = 1024)
            String senha) {}

    @Schema(name = "UsuarioAtual", description = "Usuário da sessão e suas permissões vigentes")
    record UsuarioAtualResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome,
            @Schema(requiredMode = REQUIRED) String email,
            @Schema(requiredMode = REQUIRED) List<Perfil> perfis,

            @Schema(requiredMode = REQUIRED, example = "[\"usuarios:consultar\"]")
            List<String> permissoes) {}

    @Schema(name = "Csrf")
    record CsrfResponse(
            @Schema(requiredMode = REQUIRED, example = "X-XSRF-TOKEN")
            String cabecalho) {}

    @Schema(name = "Mensagem")
    record MensagemResponse(@Schema(requiredMode = REQUIRED) String mensagem) {}

    @Schema(name = "AlteracaoSenhaRequest")
    record AlteracaoSenhaRequest(
            @Schema(requiredMode = REQUIRED, format = "password") @NotNull @Size(max = 1024)
            String senhaAtual,

            @Schema(requiredMode = REQUIRED, format = "password") @NotNull @Size(max = 1024)
            String novaSenha) {}

    @Schema(name = "RecuperacaoSenhaRequest")
    record RecuperacaoSenhaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 320)
            String email) {}

    @Schema(name = "RedefinicaoSenhaRequest")
    record RedefinicaoSenhaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 100)
            String token,

            @Schema(requiredMode = REQUIRED, format = "password") @NotNull @Size(max = 1024)
            String novaSenha) {}

    @Schema(name = "AceiteConviteRequest")
    record AceiteConviteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 100)
            String token,

            @Schema(requiredMode = REQUIRED, format = "password") @NotNull @Size(max = 1024)
            String senha) {}

    @Schema(name = "ValidacaoTokenRequest")
    record ValidacaoTokenRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 100)
            String token,

            @Schema(requiredMode = REQUIRED) @NotNull FinalidadeToken finalidade) {}

    @Schema(name = "TokenValido")
    record TokenValidoResponse(
            @Schema(requiredMode = REQUIRED) FinalidadeToken finalidade,
            @Schema(requiredMode = REQUIRED) String nome) {}

    @Schema(name = "Usuario", description = "Dados administrativos de um usuário")
    record UsuarioResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome,
            @Schema(requiredMode = REQUIRED) String email,
            @Schema(requiredMode = REQUIRED) SituacaoConta situacao,
            @Schema(requiredMode = REQUIRED) List<Perfil> perfis,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            Instant ativadoEm,
            Instant desativadoEm) {

        static UsuarioResponse de(Usuario u) {
            return new UsuarioResponse(
                    u.getId(),
                    u.getNome(),
                    u.getEmail(),
                    u.getSituacao(),
                    List.copyOf(u.getPerfis()),
                    u.getCriadoEm(),
                    u.getAtivadoEm(),
                    u.getDesativadoEm());
        }
    }

    @Schema(name = "PaginaUsuarios")
    record PaginaUsuariosResponse(
            @Schema(requiredMode = REQUIRED) List<UsuarioResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {

        static PaginaUsuariosResponse de(Page<Usuario> page) {
            return new PaginaUsuariosResponse(
                    page.getContent().stream().map(UsuarioResponse::de).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    @Schema(name = "ConviteRequest")
    record ConviteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 150)
            String nome,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 320)
            String email,

            @Schema(description = "Perfis iniciais; exige usuarios:atribuir-perfis")
            Set<Perfil> perfis) {}

    @Schema(name = "ConviteCriado")
    record ConviteCriadoResponse(
            @Schema(requiredMode = REQUIRED) UsuarioResponse usuario,

            @Schema(requiredMode = REQUIRED, description = "false quando o e-mail não pôde ser enviado")
            boolean conviteEnviado) {}

    @Schema(name = "ReenvioConvite")
    record ReenvioConviteResponse(
            @Schema(requiredMode = REQUIRED) boolean conviteEnviado) {}

    @Schema(name = "EdicaoUsuarioRequest", description = "Somente o nome é editável nesta etapa")
    record EdicaoUsuarioRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 150)
            String nome) {}

    @Schema(name = "PerfisRequest")
    record PerfisRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Set<Perfil> perfis) {}

    @Schema(name = "PerfilDisponivel")
    record PerfilResponse(
            @Schema(requiredMode = REQUIRED) Perfil codigo,
            @Schema(requiredMode = REQUIRED) String nome,
            @Schema(requiredMode = REQUIRED) List<String> permissoes) {

        static PerfilResponse de(Perfil p) {
            return new PerfilResponse(
                    p,
                    p.nome(),
                    p.permissoes().stream().map(Permissao::autoridade).toList());
        }
    }
}

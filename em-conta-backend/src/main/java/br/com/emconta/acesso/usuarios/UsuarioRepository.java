package br.com.emconta.acesso.usuarios;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String emailNormalizado);

    /** Só id e nome, sem carregar perfis. */
    @Query("select u.id as id, u.nome as nome from Usuario u where u.id in :ids")
    List<NomeUsuario> nomes(Collection<Long> ids);

    interface NomeUsuario {
        Long getId();

        String getNome();
    }

    /**
     * Trava (SELECT ... FOR UPDATE) os administradores ativos, em ordem de id. Toda operação que pode
     * reduzir o número de administradores ativos chama este método primeiro, serializando-as.
     */
    @Query(
            value = "select u.id from usuario u join usuario_perfil p on p.usuario_id = u.id"
                    + " where p.perfil = 'ADMINISTRADOR' and u.situacao = 'ATIVO' order by u.id for update",
            nativeQuery = true)
    List<Long> travarAdministradoresAtivos();

    /** Trava o intervalo do índice de perfil de administrador (usado pelo bootstrap). */
    @Query(
            value = "select usuario_id from usuario_perfil where perfil = 'ADMINISTRADOR' for update",
            nativeQuery = true)
    List<Long> travarPerfilAdministrador();

    @Query("select count(u) > 0 from Usuario u join u.perfis p"
            + " where p = br.com.emconta.acesso.usuarios.Perfil.ADMINISTRADOR"
            + " and u.situacao <> br.com.emconta.acesso.usuarios.SituacaoConta.DESATIVADO")
    boolean existeAdministradorNaoDesativado();
}

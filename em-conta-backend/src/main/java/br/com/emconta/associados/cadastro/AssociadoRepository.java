package br.com.emconta.associados.cadastro;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AssociadoRepository extends JpaRepository<Associado, Long> {

    @Query("select a.id from Associado a where a.documento = :documento")
    Optional<Long> idPorDocumento(String documento);

    /**
     * Trava o associado (SELECT ... FOR UPDATE) para serializar inclusões de contatos e UCs, sem
     * alterar a versão do cadastro.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Associado a where a.id = :id")
    Optional<Associado> travar(long id);
}

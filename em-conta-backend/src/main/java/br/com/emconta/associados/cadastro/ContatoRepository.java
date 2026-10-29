package br.com.emconta.associados.cadastro;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ContatoRepository extends JpaRepository<Contato, Long> {

    @Query("select c from Contato c where c.associadoId = :associadoId and c.removidoEm is null order by c.id")
    List<Contato> ativos(long associadoId);
}

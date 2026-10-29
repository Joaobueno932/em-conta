package br.com.emconta.associados.cadastro;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface UnidadeConsumidoraRepository extends JpaRepository<UnidadeConsumidora, Long> {

    List<UnidadeConsumidora> findByAssociadoIdOrderByIdAsc(long associadoId);

    long countByAssociadoId(long associadoId);
}

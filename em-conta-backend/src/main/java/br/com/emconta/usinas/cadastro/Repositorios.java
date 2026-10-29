package br.com.emconta.usinas.cadastro;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface FazendaRepository extends JpaRepository<Fazenda, Long> {

    @Query(
            "select f from Fazenda f where f.chaveNome = :chave and (:ignorar is null or f.id <> :ignorar) order by f.id")
    List<Fazenda> comMesmoNome(String chave, Long ignorar);
}

interface UsinaRepository extends JpaRepository<Usina, Long> {

    @Query("select u from Usina u where u.chaveNome = :chave and (:ignorar is null or u.id <> :ignorar) order by u.id")
    List<Usina> comMesmoNome(String chave, Long ignorar);
}

interface VinculoUcRepository extends JpaRepository<VinculoUc, Long> {

    /**
     * Vínculos da UC lidos com trava (SELECT ... FOR UPDATE). A leitura com trava devolve o estado
     * atual, não o instantâneo da transação; por isso as checagens de vigência usam estas linhas.
     * Chamar depois de travar a UC, que é quem serializa as operações.
     */
    @Query(
            value = "select id, usina_id as usinaId, inicio, fim from vinculo_uc_usina where uc_id = :ucId"
                    + " for update",
            nativeQuery = true)
    List<LinhaVinculo> travarDaUc(long ucId);

    interface LinhaVinculo {
        Long getId();

        Long getUsinaId();

        LocalDate getInicio();

        LocalDate getFim();
    }

    @Query(
            "select v from VinculoUc v where v.associadoId = :associadoId order by case when v.fim is null then 0 else 1 end, v.inicio desc, v.id desc")
    List<VinculoUc> doAssociado(long associadoId);

    @Query("select v from VinculoUc v where v.ucId in :ucIds and v.fim is null")
    List<VinculoUc> vigentesDasUcs(List<Long> ucIds);
}

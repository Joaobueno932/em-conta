package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Atualização de uma base na versão anterior: aplica V1–V3, grava dados como os da base de trabalho
 * e então migra até a versão atual, conferindo que nada se perdeu e que as novas regras valem.
 */
class MigracaoV4IntegrationTests extends ApiIntegracao {

    @Autowired
    MySQLContainer mysql;

    @Test
    void migraBaseNaVersao3PreservandoDados() {
        String schema = "migracao_v4";
        JdbcClient root;
        try (HikariDataSource admin = dataSource("")) {
            root = JdbcClient.create(admin);
            root.sql("drop database if exists " + schema).update();
            root.sql("create database " + schema + " character set utf8mb4 collate utf8mb4_0900_ai_ci")
                    .update();
        }
        try (HikariDataSource ds = dataSource(schema)) {
            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .target("3")
                    .load()
                    .migrate();
            JdbcClient db = JdbcClient.create(ds);
            db.sql("insert into usuario (id, nome, email, situacao, criado_em, atualizado_em)"
                            + " values (1, 'Autora', 'autora@teste.local', 'CONVIDADO', now(6), now(6))")
                    .update();
            db.sql("insert into associado (id, tipo, documento, nome, criado_em, criado_por, atualizado_em,"
                            + " atualizado_por) values (1, 'PF', '52998224725', 'Titular', now(6), 1, now(6), 1)")
                    .update();
            db.sql(
                            "insert into unidade_consumidora (id, associado_id, concessionaria, numero, criado_em, criado_por,"
                                    + " atualizado_em, atualizado_por) values (1, 1, 'ENERGISA_MS', '000123', now(6), 1, now(6), 1)")
                    .update();

            var resultado = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            assertThat(resultado.migrationsExecuted).isEqualTo(1);
            assertThat(resultado.targetSchemaVersion).isEqualTo("4");

            assertThat(db.sql("select numero from unidade_consumidora where id = 1")
                            .query(String.class)
                            .single())
                    .isEqualTo("000123");
            assertThat(db.sql("select nome from associado where id = 1")
                            .query(String.class)
                            .single())
                    .isEqualTo("Titular");

            db.sql("insert into usina (id, nome, chave_nome, criado_em, criado_por, atualizado_em, atualizado_por)"
                            + " values (1, 'Usina', 'usina', now(6), 1, now(6), 1), (2, 'Outra', 'outra', now(6), 1,"
                            + " now(6), 1)")
                    .update();
            db.sql("insert into vinculo_uc_usina (usina_id, uc_id, associado_id, inicio, criado_em, criado_por)"
                            + " values (1, 1, 1, '2026-10-01', now(6), 1)")
                    .update();
            // Segundo vínculo vigente para a mesma UC é recusado pelo banco.
            assertThatThrownBy(() -> db.sql("insert into vinculo_uc_usina (usina_id, uc_id, associado_id, inicio,"
                                    + " criado_em, criado_por) values (2, 1, 1, '2026-10-02', now(6), 1)")
                            .update())
                    .hasMessageContaining("vinculo_uc_vigente_uk");
            // Encerrado, libera um novo vínculo.
            db.sql("update vinculo_uc_usina set fim = '2026-10-05', motivo_encerramento = 'Troca',"
                            + " encerrado_em = now(6), encerrado_por = 1 where uc_id = 1")
                    .update();
            db.sql("insert into vinculo_uc_usina (usina_id, uc_id, associado_id, inicio, criado_em, criado_por)"
                            + " values (2, 1, 1, '2026-10-05', now(6), 1)")
                    .update();
            assertThat(db.sql("select count(*) from vinculo_uc_usina where uc_id = 1")
                            .query(Long.class)
                            .single())
                    .isEqualTo(2);
        }
        try (HikariDataSource admin = dataSource("")) {
            JdbcClient.create(admin).sql("drop database " + schema).update();
        }
    }

    /** Conexão como root do container de teste (a senha do root é a do usuário de teste). */
    private HikariDataSource dataSource(String schema) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + schema
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true"
                + "&useSSL=false");
        ds.setUsername("root");
        ds.setPassword(mysql.getPassword());
        ds.setMaximumPoolSize(2);
        return ds;
    }
}

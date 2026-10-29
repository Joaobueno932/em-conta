package br.com.emconta.acesso.usuarios;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Modo de bootstrap ({@code --spring.profiles.active=local,bootstrap-admin}). Nome e e-mail vêm de
 * {@code emconta.bootstrap.nome/email}; a senha é lida do terminal sem eco (digitada duas vezes) ou,
 * sem terminal, da primeira linha da entrada padrão. A senha nunca é aceita como argumento ou
 * variável de ambiente e nunca é registrada em log.
 */
@Component
@ConditionalOnProperty(name = "emconta.bootstrap.habilitado", havingValue = "true")
class BootstrapAdministradorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdministradorRunner.class);

    private final BootstrapAdministrador bootstrap;
    private final ConfigurableApplicationContext contexto;
    private final String nome;
    private final String email;

    BootstrapAdministradorRunner(
            BootstrapAdministrador bootstrap,
            ConfigurableApplicationContext contexto,
            @Value("${emconta.bootstrap.nome:}") String nome,
            @Value("${emconta.bootstrap.email:}") String email) {
        this.bootstrap = bootstrap;
        this.contexto = contexto;
        this.nome = nome;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (nome.isBlank() || email.isBlank()) {
            throw new IllegalStateException("Informe --emconta.bootstrap.nome=... e --emconta.bootstrap.email=...");
        }
        String senha = lerSenha();
        long id = bootstrap.criar(nome, email, senha);
        log.info("Administrador inicial criado (id {}). Entre no sistema com o e-mail informado.", id);
        // Comando de execução única: fecha o contexto para encerrar threads não daemon (ex.: o
        // agendador padrão do Spring Session), senão a JVM continuaria ativa após o sucesso.
        SpringApplication.exit(contexto);
    }

    private static String lerSenha() throws IOException {
        Console console = System.console();
        if (console != null && console.isTerminal()) {
            char[] primeira = console.readPassword("Senha do administrador (mínimo %d caracteres): ", 15);
            char[] segunda = console.readPassword("Repita a senha: ");
            try {
                if (primeira == null || !Arrays.equals(primeira, segunda)) {
                    throw new IllegalStateException("As senhas não conferem; nada foi alterado.");
                }
                return new String(primeira);
            } finally {
                if (primeira != null) Arrays.fill(primeira, ' ');
                if (segunda != null) Arrays.fill(segunda, ' ');
            }
        }
        log.info("Sem terminal interativo: lendo a senha da primeira linha da entrada padrão.");
        BufferedReader entrada = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String linha = entrada.readLine();
        if (linha == null) {
            throw new IllegalStateException("Nenhuma senha recebida na entrada padrão.");
        }
        // Remove apenas o terminador de linha do Windows, se houver; espaços da senha são preservados.
        return linha.endsWith("\r") ? linha.substring(0, linha.length() - 1) : linha;
    }
}

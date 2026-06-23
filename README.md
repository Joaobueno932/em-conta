# Lixo Zero ESG

Site institucional do chatbot de descarte correto de resíduos, com foco em ESG e Projeto Lixo Zero.

## Personagens

- **Eva** (E — Environmental): assistente principal do chatbot
- **Sofia** (S — Social): representante do pilar social
- **Gabi** (G — Governance): representante do pilar de governança

## Estrutura

```
index.html          → site principal (único arquivo HTML)
public/
  images/
    eva-perfil.png       → foto de perfil da Eva
    sofia-perfil.png     → foto de perfil da Sofia
    gabi-perfil.png      → foto de perfil da Gabi
    esg-trio.png         → as três juntas (com labels)
    esg-trio-clean.png   → as três juntas (sem labels)
    eva-hero.png         → Eva para seção hero e chat
assets/              → imagens originais (fonte)
```

## Como rodar

Abra o `index.html` diretamente no navegador ou sirva com qualquer servidor estático.

```bash
# Exemplo com Python
python -m http.server 8000
# Ou com Node
npx serve .
```

## Chatbot

Integrado via Botpress Webchat (widget flutuante). Atendimento realizado pela Eva.

# Imagem do frontend: build estático servido pelo Nginx, que também encaminha /api ao backend
# (mesma origem para cookies de sessão e CSRF).
#
#   docker build -t em-conta-frontend .
#   docker run -p 127.0.0.1:8081:8080 -e API_UPSTREAM=http://backend:8080 em-conta-frontend
#
# Redes com inspeção TLS (opcional, não fica na imagem final):
#   docker build --secret id=extra_ca,src=caminho/ca.pem -t em-conta-frontend .

FROM node:24.22.1-alpine AS build
WORKDIR /src
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/root/.npm \
    if [ -f /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine
# Configuração com variáveis resolvidas pelo entrypoint oficial do Nginx (envsubst).
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY deploy/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY deploy/nginx/40-em-conta-config.sh /docker-entrypoint.d/40-em-conta-config.sh
COPY --from=build /src/dist /usr/share/nginx/html
RUN chmod +x /docker-entrypoint.d/40-em-conta-config.sh
ENV API_UPSTREAM=http://backend:8080 \
    EM_CONTA_ENVIRONMENT=producao \
    NGINX_ENVSUBST_FILTER=^API_UPSTREAM$
EXPOSE 8080

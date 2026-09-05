FROM node:22-alpine AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

ARG VITE_API_BASE_URL=/api/v1
ARG VITE_AUTH_MODE=oidc
ARG VITE_OIDC_AUTHORITY
ARG VITE_OIDC_CLIENT_ID
ARG VITE_OIDC_REDIRECT_URI
ARG VITE_OIDC_POST_LOGOUT_REDIRECT_URI
ARG VITE_OIDC_SCOPE="openid profile email"
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_AUTH_MODE=${VITE_AUTH_MODE} \
    VITE_OIDC_AUTHORITY=${VITE_OIDC_AUTHORITY} \
    VITE_OIDC_CLIENT_ID=${VITE_OIDC_CLIENT_ID} \
    VITE_OIDC_REDIRECT_URI=${VITE_OIDC_REDIRECT_URI} \
    VITE_OIDC_POST_LOGOUT_REDIRECT_URI=${VITE_OIDC_POST_LOGOUT_REDIRECT_URI} \
    VITE_OIDC_SCOPE=${VITE_OIDC_SCOPE}

COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# A maintenance checkout can have umask 077. This non-secret config must remain
# readable by the unprivileged runtime, independently of host file permissions.
COPY --chmod=0644 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

USER 101:101

RUN test -r /etc/nginx/conf.d/default.conf && test -r /usr/share/nginx/html/index.html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]

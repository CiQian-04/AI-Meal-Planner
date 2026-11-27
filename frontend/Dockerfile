# syntax=docker/dockerfile:1
#
# Multi-stage build for the Vue 3 frontend, served by nginx.

# ---------- Stage 1: build ----------
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- Stage 2: nginx ----------
FROM nginx:1.27-alpine AS runtime

# Upgrade base packages so CVEs published after the base image build
# (e.g. brotli-libs CVE-2026-111497) are patched at build time.
RUN apk upgrade --no-cache

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://localhost/ >/dev/null || exit 1"]

CMD ["nginx", "-g", "daemon off;"]

FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Unprivileged variant: listens on 8080 and runs as uid 101, never root.
FROM nginxinc/nginx-unprivileged:1.28-alpine
COPY nginx/snippets/ /etc/nginx/snippets/
COPY nginx/templates/ /etc/nginx/templates/
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 8080 8443

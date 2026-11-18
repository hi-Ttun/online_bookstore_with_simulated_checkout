# Frontend: build bằng Node, phục vụ file tĩnh bằng nginx (multi-stage build).
# Cloud Run: container lắng nghe cổng $PORT (mặc định 8080); cấu hình truyền qua biến môi trường lúc chạy,
# nên MỘT image dùng được cho mọi môi trường (không phải build lại khi đổi API_URL).
#
#   docker build -t bookstore-frontend frontend/
#   docker run -p 8081:8080 -e API_URL=https://<api-url> -e AUTH_MODE=firebase ... bookstore-frontend

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/40-app-config.sh /docker-entrypoint.d/40-app-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
RUN chmod +x /docker-entrypoint.d/40-app-config.sh
ENV PORT=8080
EXPOSE 8080

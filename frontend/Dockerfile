# =========================
# 1) Build stage
# =========================
FROM node:22-alpine AS build

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm ci

# Copy the rest of the app and build
COPY . .
RUN npm run build


# =========================
# 2) Runtime stage (nginx)
# =========================
FROM nginx:1.27-alpine

# Copy built assets
COPY --from=build /app/dist /usr/share/nginx/html

# Custom nginx config (with /api proxy to backend)
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
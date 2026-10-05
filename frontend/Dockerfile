# Étape 1 : build Angular
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# Les services appellent http://localhost:8080 en dur : on passe par le proxy nginx (/api)
RUN sed -i 's#http://localhost:8080#/api#g' src/app/services/*.ts && npx ng build

# Étape 2 : nginx sert les fichiers statiques
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80

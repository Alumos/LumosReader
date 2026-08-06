FROM node:26-alpine AS web
WORKDIR /src/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM golang:1.26-alpine AS server
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY --from=web /src/web/dist ./web/dist
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /lumosreader .

FROM scratch
COPY --from=server /lumosreader /lumosreader
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/lumosreader"]

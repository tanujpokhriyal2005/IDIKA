FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 app && mkdir -p /data/uploads && chown app /data/uploads
USER app
COPY --from=build /out/api /usr/local/bin/api
ENV UPLOAD_DIR=/data/uploads PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/api"]

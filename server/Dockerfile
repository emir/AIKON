# Claude S40 server: phone-facing TLS + chat backend + SQLite, one static
# binary on distroless (non-root). Tests run during the build.
# Certificates, keys and the database are NOT in the image.
FROM golang:1.26-alpine@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c AS build
RUN apk add --no-cache openssl
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN go vet ./... && go test -count=1 ./... \
 && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -buildid=" -o /out/claude-s40-server . \
 && mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/claude-s40-server /claude-s40-server
# empty, nonroot-owned /data so a fresh named volume is writable
COPY --from=build --chown=65532:65532 /out/data /data
USER nonroot:nonroot
EXPOSE 8443 9090
ENTRYPOINT ["/claude-s40-server"]

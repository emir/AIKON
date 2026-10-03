# AIKON server: phone-facing TLS + chat backend + SQLite, one static Go
# binary on distroless (non-root), plus a minimal static ffmpeg for voice
# messages (AMR from the phone -> PCM for speech-to-text). That ffmpeg is
# built from the pinned source with only the AMR/WAV demuxers and decoders,
# raw PCM output and the pipe protocol: no network, no other formats. It
# runs on pipes, never on files. Tests run during the build, with this ffmpeg.
# Certificates, keys and the database are NOT in the image.
FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6 AS ffmpeg
RUN apk add --no-cache build-base
ADD --checksum=sha256:8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e \
    https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz /src/ffmpeg.tar.xz
WORKDIR /src
RUN tar xf ffmpeg.tar.xz && cd ffmpeg-9.0.2 \
 && ./configure --disable-everything --disable-autodetect --disable-network --disable-doc --disable-debug \
      --disable-ffplay --disable-ffprobe --disable-x86asm --enable-static --disable-shared \
      --extra-ldflags=-static --enable-swresample \
      --enable-demuxer=amr,wav --enable-parser=amr --enable-protocol=pipe \
      --enable-decoder=amrnb,pcm_s16le,pcm_u8,pcm_alaw,pcm_mulaw,adpcm_ima_wav \
      --enable-encoder=pcm_s16le --enable-muxer=pcm_s16le \
      --enable-filter=aresample,aformat,anull,atrim,abuffer,abuffersink >/dev/null \
 && make -j"$(nproc)" ffmpeg >/dev/null && strip ffmpeg && cp ffmpeg /ffmpeg

FROM golang:1.26-alpine@sha256:8ac98ca534ac3f51e1f420a1dd2c15e74c75cfa0f23f3ad27eb5d7236c349a0c AS build
RUN apk add --no-cache openssl
COPY --from=ffmpeg /ffmpeg /usr/local/bin/ffmpeg
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY lang ./lang
RUN go vet ./... && go test -count=1 ./... \
 && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -buildid=" -o /out/claude-s40-server . \
 && mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/claude-s40-server /claude-s40-server
COPY --from=ffmpeg /ffmpeg /usr/local/bin/ffmpeg
# empty, nonroot-owned /data so a fresh named volume is writable
COPY --from=build --chown=65532:65532 /out/data /data
USER nonroot:nonroot
EXPOSE 8443 9090
ENTRYPOINT ["/claude-s40-server"]

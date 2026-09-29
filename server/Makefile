.PHONY: test docker
test:
	go vet ./...
	go test -race -count=1 ./...

# linux/amd64 image for a typical VPS; tests run inside the build too
docker:
	docker buildx build --platform linux/amd64 -t claude-s40-server:0.5.0 --load .

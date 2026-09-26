.PHONY: all test app server-test docker clean
all: test

test: server-test app

server-test:
	$(MAKE) -C server test

app:
	$(MAKE) -C app

docker:
	$(MAKE) -C server docker

clean:
	$(MAKE) -C app clean

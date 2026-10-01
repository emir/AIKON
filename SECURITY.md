# Security

## Known, accepted trade-offs

- **TLS 1.0 with CBC suites between phone and server.** The phone supports
  nothing newer. The connection is still encrypted and authenticated with
  your private root CA, but it is weaker than today's standard. The server
  offers modern TLS to modern clients and never RC4 or 3DES.
- **The Claude API key is on your server.** Use a dedicated key with a
  spending limit, keep SSH restricted, keep the host patched.
- **Your root CA's private key** stays on the machine where you created it
  (`~/.config/claude-s40/pki`). Anyone with it can impersonate your server to
  your phone. Back it up offline; never copy it to the server.
- **Pairing start is unauthenticated** (anyone can request a code). Codes
  are useless without approval through the admin API, which is bound to
  127.0.0.1 and requires a server-side token; at most 3 pairings can be
  pending at once.

## Reporting

Please report vulnerabilities privately through GitHub's "Report a
vulnerability" on https://github.com/emir/aikon/security instead of a
public issue.

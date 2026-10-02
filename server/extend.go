package main

// Extension points. A build can add phone routes, admin routes and /health
// fields from init functions in files of its own (and install its meter
// through meterFactory), so the shared files stay as they are. Nothing is
// registered here: the default server has no extensions.

import (
	"context"
	"net/http"
)

var (
	// extraRoutes add handlers to the phone-facing mux.
	extraRoutes []func(s *server, mux *http.ServeMux)
	// extraAdminRoutes add handlers to the admin mux (localhost only).
	extraAdminRoutes []func(s *server, mux *http.ServeMux)
	// extraHealth add fields to GET /health.
	extraHealth []func(s *server) []kv
	// extraPaths are logged by name (any other unknown path as "(other)").
	extraPaths = map[string]bool{}
)

// meterFields: a meter may add fields (for example a balance) to the
// answers of chat and voice messages.
type meterFields interface {
	fields(ctx context.Context, device string) []kv
}

// meterCosts: a meter may tell what a typical message costs with a model
// for this device ("" = nothing is charged); /v1/models sends it to phones
// that ask with "costs: 1".
type meterCosts interface {
	messageCost(ctx context.Context, device, model string) string
}

func addMeterFields(ctx context.Context, m meter, device string, f []kv) []kv {
	if mf, ok := m.(meterFields); ok {
		return append(f, mf.fields(ctx, device)...)
	}
	return f
}

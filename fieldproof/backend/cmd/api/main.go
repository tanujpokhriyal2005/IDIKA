package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"fieldproof/backend/internal/api"

	"github.com/jackc/pgx/v5/pgxpool"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dsn := env("DATABASE_URL", "postgres://fieldproof:fieldproof@localhost:5432/fieldproof?sslmode=disable")
	var db *pgxpool.Pool
	var err error
	for i := 0; i < 30; i++ { // wait for Postgres on first boot
		db, err = pgxpool.New(ctx, dsn)
		if err == nil {
			if err = db.Ping(ctx); err == nil {
				break
			}
			db.Close()
		}
		log.Printf("waiting for database: %v", err)
		time.Sleep(2 * time.Second)
	}
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer db.Close()

	if err := api.Migrate(ctx, db); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	// Every village and surveyor needs a state for the state leads' views.
	if err := api.FillStates(ctx, db); err != nil {
		log.Printf("fill states: %v", err)
	}
	go api.FillStatesByLookup(ctx, db)
	cfg := api.Config{
		DevMode:       env("DEV_MODE", "true") == "true",
		UploadDir:     env("UPLOAD_DIR", "./uploads"),
		AdminUser:     env("ADMIN_USER", "admin"),
		AdminPassword: env("ADMIN_PASSWORD", "fieldproof"),
		WebDir:        os.Getenv("WEB_DIR"),
	}
	if err := os.MkdirAll(cfg.UploadDir, 0o755); err != nil {
		log.Fatal(err)
	}
	if env("SEED_DEMO", "true") == "true" {
		if err := api.Seed(ctx, db); err != nil {
			log.Fatalf("seed: %v", err)
		}
	}
	// First dashboard admin comes from ADMIN_USER / ADMIN_PASSWORD.
	if err := api.EnsureAdmin(ctx, db, cfg.AdminUser, cfg.AdminPassword); err != nil {
		log.Fatalf("admin: %v", err)
	}
	if env("SEED_DASHBOARD_DEMO", env("SEED_DEMO", "true")) == "true" {
		if err := api.SeedDashboard(ctx, db, cfg.UploadDir); err != nil {
			log.Fatalf("seed dashboard: %v", err)
		}
		if err := api.SeedPlantation(ctx, db, cfg.UploadDir); err != nil {
			log.Fatalf("seed plantation: %v", err)
		}
		if err := api.SeedLandChange(ctx, db, cfg.UploadDir); err != nil {
			log.Fatalf("seed land change: %v", err)
		}
	}
	srv := &http.Server{
		Addr:              ":" + env("PORT", "8080"),
		Handler:           api.New(db, cfg),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(sctx)
	}()
	log.Printf("fieldproof api listening on %s (dev mode %v)", srv.Addr, cfg.DevMode)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

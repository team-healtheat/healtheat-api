# EC2 deployment

The `deploy/compose.yaml` stack runs PostgreSQL, the API, and Caddy on one EC2 instance. PostgreSQL and the API have no public host ports; only Caddy exposes HTTP and HTTPS. Database and Caddy data use named volumes. The server's `deploy/.env` holds secrets, is ignored by Git, and must be readable only by its owner.

The first deployment requires an existing server, Docker with the Compose plugin, a trusted hostname resolving to the server, ports 80 and 443, an application JAR, and a server-side `.env` based on `.env.example`. Run `sudo docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build` from the project directory. Caddy requests a certificate automatically; an IP-derived test hostname may change when EC2 is stopped and started.

GitHub Actions tests and builds on a GitHub-hosted runner. A successful push to `main` then runs the deployment job on a dedicated self-hosted Linux x64 runner labelled `healtheat-deploy`, installed as `ec2-user` on the EC2 host. No runner has been installed by this repository. Register and secure that runner separately before expecting automatic deployment. Do not assign untrusted pull-request jobs to it: the runner can update the server. The deploy script preserves the server `.env` and database volume, rebuilds only the application image, and checks the HTTPS OpenAPI endpoint. Database migrations still need a backup and a recovery plan; application image rollback alone does not undo database migrations.

The API uses forwarded headers because Caddy terminates HTTPS. Do not expose the app's port 8080 directly to the internet, as forwarded headers must come from the trusted proxy. The small EC2 instance has limited memory and disk; monitor resource use and do not build the application with Gradle on the server.

`deploy/backup.sh` is intended to create a private PostgreSQL dump on the EC2 disk; its live execution and restore still need verification. It is a manual backup, not a scheduled or off-server backup, and does not protect against instance or disk loss. Backup scheduling, retention and off-server storage must be planned before relying on the server for real users. Avoid running `docker compose down -v`, which removes the named database volume.

Before sharing with Android users, configure the OpenAI key privately on the server, validate login and AI generation over HTTPS, and determine the Android client's cookie handling. `AI_CHAT_MODEL=none` deliberately returns an error for AI features rather than calling a provider.

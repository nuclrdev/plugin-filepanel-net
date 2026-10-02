#!/bin/bash
# Realistic content for the test server, built into the image so every reset
# starts from exactly this state. Nothing here is a real secret.
set -euo pipefail

H=/home/tester

# An application folder like the ones agents will be asked to work on.
mkdir -p "$H/app/my folder with spaces" "$H/logs" "$H/data"
cat > "$H/app/.env" <<'ENV'
APP_ENV=test
DB_HOST=127.0.0.1
DB_PASSWORD=test-not-a-real-password
API_TOKEN=fake-token-123
ENV
chmod 600 "$H/app/.env"

cat > "$H/app/deploy.sh" <<'SH'
#!/bin/sh
echo "deploying from $(pwd)"
SH
chmod 755 "$H/app/deploy.sh"

cat > "$H/app/config.yml" <<'YML'
server:
  port: 8080
  workers: 4
database:
  host: 127.0.0.1
  password: test-not-a-real-password
YML

echo "A file in a folder whose name has spaces." > "$H/app/my folder with spaces/readme.txt"

for i in $(seq 1 5000); do
	printf '2026-10-02T10:%02d:%02dZ INFO request %d served in %dms\n' $((i / 60 % 60)) $((i % 60)) "$i" $((i % 97))
done > "$H/logs/app.log"
echo "2026-10-02T11:23:20Z ERROR database connection refused" >> "$H/logs/app.log"

head -c 65536 /dev/urandom > "$H/data/blob.bin"

ln -s app "$H/current"
ln -s app.log "$H/logs/latest.log"
chown -R tester:tester "$H"

# Places the tester cannot change without sudo, as on a real server.
mkdir -p /etc/nginx/sites-available /srv/www /var/lib/fake-mysql
cat > /etc/nginx/sites-available/site <<'NGINX'
server {
    listen 80;
    root /srv/www;
}
NGINX
echo "<h1>Nuclr test server</h1>" > /srv/www/index.html
echo "ibdata" > /var/lib/fake-mysql/ibdata1
chmod 700 /var/lib/fake-mysql

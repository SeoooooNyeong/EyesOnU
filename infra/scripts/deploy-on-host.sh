#!/usr/bin/env bash
set -Eeuo pipefail

profile="${1:-}"
deploy_root="${DEPLOY_ROOT:-/home/ubuntu/jenkins-data/workspace/ssafy-a204-infra}"
deploy_env_file="${DEPLOY_ENV_FILE:-/home/ubuntu/S15P11A204/infra/.env.deploy}"
compose_file="${DEPLOY_COMPOSE:-$deploy_root/infra/compose.deploy.yml}"

case "$profile" in
    dev|master) ;;
    *)
        echo "Usage: $0 {dev|master}" >&2
        exit 2
        ;;
esac

service_names=()
if [[ -n "${DEPLOY_SERVICES:-}" ]]; then
    read -r -a service_names <<< "$DEPLOY_SERVICES"
else
    # Keep manual deployments backward-compatible: no service list means all apps.
    service_names=(backend admin reporter)
fi

services=()
for service in "${service_names[@]}"; do
    case "$service" in
        backend|admin|reporter)
            services+=("$service-$profile")
            ;;
        *)
            echo "ERROR: unsupported service: $service" >&2
            exit 2
            ;;
    esac
done

compose() {
    docker compose \
        --env-file "$deploy_env_file" \
        -f "$compose_file" \
        "$@"
}

require_file() {
    local path="$1"

    if [[ ! -f "$path" ]]; then
        echo "ERROR: required file is missing: $path" >&2
        exit 1
    fi
}

require_file "$deploy_env_file"
require_file "$compose_file"
require_file "$deploy_root/infra/nginx/conf.d/default.conf"
compose config >/dev/null

cd "$deploy_root"
sh infra/scripts/cleanup-legacy-containers.sh
compose --profile "$profile" up -d --build --wait --wait-timeout 180 \
    "${services[@]}"
compose up -d --no-deps --wait --wait-timeout 180 nginx
compose exec -T nginx nginx -t
compose exec -T nginx nginx -s reload
compose ps

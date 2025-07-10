#!/bin/bash

set -e

AUTH_GATE_ENABLED=${OPIK_AUTH_GATE_ENABLED:-true}
AUTH_GATE_VALUE=${OPIK_AUTH_GATE_VALUE:-required}

# Generate nginx configuration
cat > /etc/nginx/conf.d/default.conf << EOF
server {
    listen 5173;
    server_name localhost;
    root /usr/share/nginx/html;
    index index.html;

    # API - validate Authorization header
    location /api/ {
        if (\$http_authorization != "$AUTH_GATE_VALUE") {
            return 403 "Access denied: Invalid API key";
        }
        
        rewrite /api/(.*) /\$1  break;
        proxy_pass http://backend:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 90;
        proxy_connect_timeout 90;
        proxy_send_timeout 90;
        proxy_http_version 1.1;
        client_max_body_size 500M;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Frontend - validate Authorization header for all requests
    location / {
        if (\$http_authorization != "$AUTH_GATE_VALUE") {
            return 403 "Access denied: Invalid API key";
        }
        
        try_files \$uri \$uri/ /index.html;
    }
}
EOF

echo "Generated nginx config with Auth Gate: enabled=$AUTH_GATE_ENABLED, value=$AUTH_GATE_VALUE"

exec nginx -g "daemon off;" 